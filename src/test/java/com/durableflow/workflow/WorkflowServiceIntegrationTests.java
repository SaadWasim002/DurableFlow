package com.durableflow.workflow;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.durableflow.workflow.WorkflowExceptions.Conflict;
import com.durableflow.workflow.WorkflowModels.CreateRunRequest;
import com.durableflow.workflow.WorkflowModels.CreateWorkflowRequest;
import com.durableflow.workflow.WorkflowModels.TaskDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "durableflow.execution.enabled=false",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class WorkflowServiceIntegrationTests {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    WorkflowService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsDefinitionAndAtomicallyInitializesIndependentRuns() {
        var definition = service.createWorkflow(new CreateWorkflowRequest("workflow-" + UUID.randomUUID(), 1,
                List.of(task("second", "first"), task("first"))));
        assertThat(definition.tasks()).extracting(TaskDefinition::id).containsExactly("first", "second");
        assertThat(service.getWorkflow(definition.id())).isEqualTo(definition);

        var first = service.createRun(definition.id(), new CreateRunRequest(Map.of("report", "one")));
        var second = service.createRun(definition.id(), new CreateRunRequest(Map.of("report", "two")));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(first.status()).isEqualTo("QUEUED");
        assertThat(first.tasks()).extracting(t -> t.taskKey()).containsExactly("first", "second");
        assertThat(first.tasks()).extracting(t -> t.status()).containsExactly("READY", "PENDING");
        assertThat(first.tasks()).extracting(t -> t.id())
                .doesNotContainAnyElementsOf(second.tasks().stream().map(t -> t.id()).toList());
        assertThat(service.getRun(first.id())).isEqualTo(first);
        assertThat(service.getTasks(first.id())).isEqualTo(first.tasks());
        assertThat(service.getAttempts(first.tasks().getFirst().id())).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execution_events WHERE workflow_run_id = ?", Long.class, first.id()))
                .isEqualTo(3L);
    }

    @Test
    void preservesOldVersionsAndMapsDuplicateVersionsToConflict() {
        String name = "workflow-" + UUID.randomUUID();
        var original = service.createWorkflow(new CreateWorkflowRequest(name, 1, List.of(task("first"))));
        assertThatThrownBy(() -> service.createWorkflow(
                new CreateWorkflowRequest(name, 1, List.of(task("replacement")))))
                .isInstanceOf(Conflict.class);
        var next = service.createWorkflow(new CreateWorkflowRequest(name, 2, List.of(task("replacement"))));
        assertThat(next.id()).isNotEqualTo(original.id());
        assertThat(service.getWorkflow(original.id()).tasks()).extracting(TaskDefinition::id)
                .containsExactly("first");
    }

    @Test
    void rollsBackTheRunTasksAndEventsWhenALaterTaskInsertFails() {
        var definition = service.createWorkflow(new CreateWorkflowRequest("workflow-" + UUID.randomUUID(), 1,
                List.of(task("first"), task("reject_persistence", "first"))));
        long eventCountBefore = jdbc.queryForObject("SELECT count(*) FROM execution_events", Long.class);
        jdbc.execute("""
                CREATE FUNCTION reject_test_task() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.task_key = 'reject_persistence' THEN
                        RAISE EXCEPTION 'Simulated late task insert failure';
                    END IF;
                    RETURN NEW;
                END;
                $$;
                CREATE TRIGGER reject_test_task BEFORE INSERT ON task_runs
                    FOR EACH ROW EXECUTE FUNCTION reject_test_task();
                """);
        try {
            assertThatThrownBy(() -> service.createRun(definition.id(), new CreateRunRequest(Map.of())))
                    .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM workflow_runs WHERE workflow_definition_id = ?",
                    Long.class, definition.id())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_events", Long.class))
                    .isEqualTo(eventCountBefore);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM task_runs WHERE task_key = 'reject_persistence'", Long.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_task ON task_runs; DROP FUNCTION reject_test_task();");
        }
    }

    private static TaskDefinition task(String id, String... parents) {
        return new TaskDefinition(id, "echo", List.of(parents), Map.of("message", id));
    }
}
