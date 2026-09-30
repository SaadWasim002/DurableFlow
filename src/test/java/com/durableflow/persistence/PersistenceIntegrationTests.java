package com.durableflow.persistence;

import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class PersistenceIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Flyway flyway;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void usesUtcForDatabaseConnections() {
        assertThat(jdbc.queryForObject("SHOW TIME ZONE", String.class)).isEqualTo("UTC");
    }

    @Test
    void appliesInitialMigrationAndDoesNotReapplyIt() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('workflow_definitions', 'workflow_runs', 'task_runs',
                                     'task_attempts', 'execution_events')
                """, Long.class)).isEqualTo(5L);
    }

    @Test
    void storesAnExecutionAndReadsItThroughANewDatabaseConnection() throws Exception {
        UUID run = createRun(createDefinition());
        UUID task = createTask(run, "prepare");
        UUID attempt = createAttempt(task, 1);
        UUID event = UUID.randomUUID();

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            jdbc.update("""
                    UPDATE task_runs SET status = 'SUCCEEDED', output = ?::jsonb,
                        started_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, "{\"report\":\"ready\"}", task);
            jdbc.update("""
                    UPDATE task_attempts SET status = 'SUCCEEDED', output = ?::jsonb,
                        finished_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, "{\"report\":\"ready\"}", attempt);
            insertEvent(event, run, task, attempt, "RUNNING", "SUCCEEDED");
        });

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT t.status, t.output ->> 'report' AS report,
                            a.status AS attempt_status, e.to_status
                     FROM task_runs t
                     JOIN task_attempts a ON a.task_run_id = t.id
                     JOIN execution_events e ON e.task_attempt_id = a.id
                     WHERE t.id = ? AND e.id = ?
                     """)) {
            statement.setObject(1, task);
            statement.setObject(2, event);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("SUCCEEDED");
                assertThat(result.getString("report")).isEqualTo("ready");
                assertThat(result.getString("attempt_status")).isEqualTo("SUCCEEDED");
                assertThat(result.getString("to_status")).isEqualTo("SUCCEEDED");
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void rejectsDuplicateDefinitionVersionsButAcceptsANewVersion() {
        String name = "workflow-" + UUID.randomUUID();
        insertDefinition(UUID.randomUUID(), name, 1, "{\"tasks\":[]}");

        assertThatThrownBy(() -> insertDefinition(UUID.randomUUID(), name, 1, "{\"tasks\":[]}"))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertDefinition(UUID.randomUUID(), name, 2, "{\"tasks\":[]}");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM workflow_definitions WHERE name = ?", Long.class, name))
                .isEqualTo(2L);
    }

    @Test
    void rejectsDuplicateTaskKeysWithinARunButAcceptsThemAcrossRuns() {
        UUID definition = createDefinition();
        UUID run = createRun(definition);
        createTask(run, "prepare");

        assertThatThrownBy(() -> createTask(run, "prepare"))
                .isInstanceOf(DataIntegrityViolationException.class);

        createTask(createRun(definition), "prepare");
    }

    @Test
    void preservesSeparateAttemptsAndRejectsDuplicateOrInvalidNumbers() {
        UUID task = createTask(createRun(createDefinition()), "prepare");
        UUID firstAttempt = createAttempt(task, 1);
        jdbc.update("""
                UPDATE task_attempts SET status = 'FAILED', error_type = 'DEMO_FAILURE',
                    error_message = 'First attempt failed', finished_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, firstAttempt);
        createAttempt(task, 2);

        assertThatThrownBy(() -> createAttempt(task, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> createAttempt(task, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject(
                "SELECT error_message FROM task_attempts WHERE id = ?", String.class, firstAttempt))
                .isEqualTo("First attempt failed");
        assertThat(jdbc.queryForList("""
                SELECT attempt_number FROM task_attempts
                WHERE task_run_id = ? ORDER BY attempt_number
                """, Integer.class, task)).containsExactly(1, 2);
    }

    @Test
    void rejectsOrphanTasksAndEventsLinkedToTheWrongRunOrAttempt() {
        assertThatThrownBy(() -> createTask(UUID.randomUUID(), "orphan"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID definition = createDefinition();
        UUID firstRun = createRun(definition);
        UUID secondRun = createRun(definition);
        UUID firstTask = createTask(firstRun, "prepare");
        UUID secondTask = createTask(secondRun, "prepare");
        UUID secondAttempt = createAttempt(secondTask, 1);

        assertThatThrownBy(() -> insertEvent(
                UUID.randomUUID(), secondRun, firstTask, null, "PENDING", "READY"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEvent(
                UUID.randomUUID(), firstRun, firstTask, secondAttempt, "READY", "RUNNING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEvent(
                UUID.randomUUID(), secondRun, null, secondAttempt, "READY", "RUNNING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rollsBackTheStateChangeAndHistoryTogetherOnFailure() {
        UUID run = createRun(createDefinition());
        UUID event = UUID.randomUUID();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(transaction -> {
                    jdbc.update("""
                            UPDATE workflow_runs SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP
                            WHERE id = ?
                            """, run);
                    insertEvent(event, run, null, null, "QUEUED", "RUNNING");
                    insertEvent(event, run, null, null, "QUEUED", "RUNNING");
                })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM workflow_runs WHERE id = ?", String.class, run))
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execution_events WHERE workflow_run_id = ?", Long.class, run))
                .isZero();
    }

    @Test
    void rejectsInvalidDefinitionShapesAndUnknownStates() {
        assertThatThrownBy(() -> insertDefinition(
                UUID.randomUUID(), "invalid-" + UUID.randomUUID(), 1, "[]"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDefinition(
                UUID.randomUUID(), "invalid-" + UUID.randomUUID(), 0, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID run = createRun(createDefinition());
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE workflow_runs SET status = 'UNKNOWN' WHERE id = ?", run))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID createDefinition() {
        UUID id = UUID.randomUUID();
        insertDefinition(id, "workflow-" + id, 1, "{\"tasks\":[{\"id\":\"prepare\",\"type\":\"echo\"}]}");
        return id;
    }

    private void insertDefinition(UUID id, String name, int version, String definition) {
        jdbc.update("""
                INSERT INTO workflow_definitions (id, name, version, definition)
                VALUES (?, ?, ?, ?::jsonb)
                """, id, name, version, definition);
    }

    private UUID createRun(UUID definition) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_runs (id, workflow_definition_id) VALUES (?, ?)", id, definition);
        return id;
    }

    private UUID createTask(UUID run, String taskKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task_runs (id, workflow_run_id, task_key, task_type)
                VALUES (?, ?, ?, 'echo')
                """, id, run, taskKey);
        return id;
    }

    private UUID createAttempt(UUID task, int number) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO task_attempts (id, task_run_id, attempt_number) VALUES (?, ?, ?)",
                id, task, number);
        return id;
    }

    private void insertEvent(UUID id, UUID run, UUID task, UUID attempt, String from, String to) {
        jdbc.update("""
                INSERT INTO execution_events
                    (id, workflow_run_id, task_run_id, task_attempt_id, from_status, to_status)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, run, task, attempt, from, to);
    }
}
