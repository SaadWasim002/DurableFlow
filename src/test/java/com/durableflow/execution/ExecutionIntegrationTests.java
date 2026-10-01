package com.durableflow.execution;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.durableflow.workflow.WorkflowModels.CreateRunRequest;
import com.durableflow.workflow.WorkflowModels.CreateWorkflowRequest;
import com.durableflow.workflow.WorkflowModels.RunResponse;
import com.durableflow.workflow.WorkflowModels.TaskDefinition;
import com.durableflow.workflow.WorkflowModels.TaskResponse;
import com.durableflow.workflow.WorkflowService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "durableflow.execution.enabled=false",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class ExecutionIntegrationTests {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired ExecutionService execution;
    @Autowired WorkflowService workflows;
    @Autowired TaskHandlers handlers;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clearIsolatedTestDatabase() {
        jdbc.execute("TRUNCATE workflow_definitions CASCADE");
    }

    @Test
    void executesTheChainWithResolvedInputsAndAtomicHistory() throws Exception {
        var run = createRun(List.of(
                task("first", "echo", List.of(), Map.of("$ref", "run.input")),
                task("second", "sleep", List.of("first"),
                        Map.of("millis", 0, "previous", Map.of("$ref", "first.output"))),
                task("third", "echo", List.of("second"), Map.of("$ref", "second.output"))));
        var first = execution.claimNext().orElseThrow();
        assertThat(first.input()).containsEntry("report", "demo");
        assertThat(workflows.getRun(run.id()).status()).isEqualTo("RUNNING");
        assertThat(execution.claimNext()).isEmpty();
        // Claim has committed and released the run lock before the handler executes.
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                jdbc.queryForObject("SELECT id FROM workflow_runs WHERE id = ? FOR UPDATE NOWAIT",
                        UUID.class, run.id()));
        assertThat(execution.succeed(first, handlers.execute(first.type(), first.input()))).isTrue();
        var second = execution.claimNext().orElseThrow();
        assertThat(second.input()).containsEntry("previous", Map.of("report", "demo"));
        execution.succeed(second, handlers.execute(second.type(), second.input()));
        var third = execution.claimNext().orElseThrow();
        execution.succeed(third, handlers.execute(third.type(), third.input()));

        var finished = workflows.getRun(run.id());
        assertThat(finished.status()).isEqualTo("SUCCEEDED");
        assertThat(finished.finishedAt()).isAfterOrEqualTo(finished.startedAt());
        assertThat(finished.tasks()).extracting(TaskResponse::status)
                .containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED");
        for (int i = 0; i < finished.tasks().size(); i++) {
            var task = finished.tasks().get(i);
            var attempts = workflows.getAttempts(task.id());
            assertThat(attempts).hasSize(1);
            assertThat(attempts.getFirst().status()).isEqualTo("SUCCEEDED");
            assertThat(attempts.getFirst().output()).isEqualTo(task.output());
            assertThat(attempts.getFirst().finishedAt()).isAfterOrEqualTo(attempts.getFirst().startedAt());
            if (i > 0) {
                assertThat(task.startedAt()).isAfterOrEqualTo(finished.tasks().get(i - 1).finishedAt());
            }
        }
        assertThat(finished.tasks().get(1).input()).containsEntry("previous", Map.of("$ref", "first.output"));
        assertThat(workflows.getAttempts(second.taskId()).getFirst().input()).isEqualTo(second.input());
        assertThat(eventCount(run.id())).isEqualTo(14);
        assertThat(execution.claimNext()).isEmpty();
    }

    @Test
    void stopsAfterMiddleFailureAndSkipsItsDescendantsWithoutAttempts() {
        var run = createRun(List.of(task("first", "echo", List.of(), Map.of()),
                task("second", "fail", List.of("first"), Map.of()),
                task("third", "echo", List.of("second"), Map.of())));
        var first = execution.claimNext().orElseThrow();
        execution.succeed(first, Map.of());
        var second = execution.claimNext().orElseThrow();
        execution.fail(second, "DEMO_FAILURE", "Simulated failure");

        var failed = workflows.getRun(run.id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.tasks()).extracting(TaskResponse::status).containsExactly("SUCCEEDED", "FAILED", "SKIPPED");
        var skipped = failed.tasks().getLast();
        assertThat(skipped.startedAt()).isNull();
        assertThat(skipped.finishedAt()).isNotNull();
        assertThat(workflows.getAttempts(skipped.id())).isEmpty();
        var attempt = workflows.getAttempts(second.taskId()).getFirst();
        assertThat(attempt.errorType()).isEqualTo("DEMO_FAILURE");
        assertThat(attempt.errorMessage()).isEqualTo("Simulated failure");
        assertThat(execution.claimNext()).isEmpty();
    }

    @Test
    void concurrentClaimsCreateOnlyOneAttempt() throws Exception {
        var run = createRun(List.of(task("first", "echo", List.of(), Map.of())));
        Callable<Boolean> claim = () -> execution.claimNext().isPresent();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.of(claim, claim));
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(workflows.getAttempts(run.tasks().getFirst().id())).hasSize(1);
    }

    @Test
    void repeatedOrWrongAttemptCompletionCannotChangeResultsOrHistory() {
        var run = createRun(List.of(task("first", "echo", List.of(), Map.of())));
        var claimed = execution.claimNext().orElseThrow();
        var wrong = new ClaimedTask(claimed.runId(), claimed.taskId(), UUID.randomUUID(),
                claimed.taskKey(), claimed.type(), claimed.input());
        assertThat(execution.succeed(wrong, Map.of("wrong", true))).isFalse();
        assertThat(execution.succeed(claimed, Map.of("saved", true))).isTrue();
        long events = eventCount(run.id());
        assertThat(execution.succeed(claimed, Map.of("replacement", true))).isFalse();
        assertThat(execution.fail(claimed, "LATE_FAILURE", "ignored")).isFalse();
        assertThat(eventCount(run.id())).isEqualTo(events);
        assertThat(workflows.getRun(run.id()).tasks().getFirst().output()).isEqualTo(Map.of("saved", true));
    }

    @Test
    void failedCompletionWriteRollsBackTaskAttemptRunAndHistory() {
        var run = createRun(List.of(task("first", "echo", List.of(), Map.of())));
        var claimed = execution.claimNext().orElseThrow();
        long before = eventCount(run.id());
        jdbc.execute("""
                CREATE FUNCTION reject_success_event() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.to_status = 'SUCCEEDED' THEN RAISE EXCEPTION 'Test event failure'; END IF;
                    RETURN NEW;
                END;
                $$;
                CREATE TRIGGER reject_success_event BEFORE INSERT ON execution_events
                    FOR EACH ROW EXECUTE FUNCTION reject_success_event();
                """);
        try {
            assertThatThrownBy(() -> execution.succeed(claimed, Map.of("value", "ok")))
                    .isInstanceOf(DataAccessException.class);
            assertThat(workflows.getRun(run.id()).status()).isEqualTo("RUNNING");
            assertThat(workflows.getRun(run.id()).tasks().getFirst().status()).isEqualTo("RUNNING");
            assertThat(workflows.getAttempts(claimed.taskId()).getFirst().status()).isEqualTo("RUNNING");
            assertThat(workflows.getAttempts(claimed.taskId()).getFirst().output()).isNull();
            assertThat(eventCount(run.id())).isEqualTo(before);
        } finally {
            jdbc.execute("DROP TRIGGER reject_success_event ON execution_events; DROP FUNCTION reject_success_event();");
        }
        assertThat(execution.succeed(claimed, Map.of("value", "ok"))).isTrue();
    }

    @Test
    void failedClaimWriteRollsBackRunStartAndTaskClaim() {
        var run = createRun(List.of(task("first", "echo", List.of(), Map.of())));
        jdbc.execute("""
                CREATE FUNCTION reject_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Test attempt failure'; END; $$;
                CREATE TRIGGER reject_attempt BEFORE INSERT ON task_attempts
                    FOR EACH ROW EXECUTE FUNCTION reject_attempt();
                """);
        try {
            assertThatThrownBy(() -> execution.claimNext()).isInstanceOf(DataAccessException.class);
            assertThat(workflows.getRun(run.id()).status()).isEqualTo("QUEUED");
            assertThat(workflows.getRun(run.id()).tasks().getFirst().status()).isEqualTo("READY");
            assertThat(workflows.getAttempts(run.tasks().getFirst().id())).isEmpty();
            assertThat(eventCount(run.id())).isEqualTo(2);
        } finally {
            jdbc.execute("DROP TRIGGER reject_attempt ON task_attempts; DROP FUNCTION reject_attempt();");
        }
    }

    @Test
    void willNotClaimATaskWhoseDependencyHasNotSucceeded() {
        var run = createRun(List.of(task("first", "echo", List.of(), Map.of()),
                task("second", "echo", List.of("first"), Map.of())));
        jdbc.update("UPDATE task_runs SET status = 'PENDING' WHERE id = ?", run.tasks().getFirst().id());
        jdbc.update("UPDATE task_runs SET status = 'READY' WHERE id = ?", run.tasks().getLast().id());
        assertThat(execution.claimNext()).isEmpty();
        assertThat(workflows.getAttempts(run.tasks().getLast().id())).isEmpty();
    }

    private RunResponse createRun(List<TaskDefinition> tasks) {
        var definition = workflows.createWorkflow(new CreateWorkflowRequest("execution-" + UUID.randomUUID(), 1, tasks));
        return workflows.createRun(definition.id(), new CreateRunRequest(Map.of("report", "demo")));
    }

    private long eventCount(UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM execution_events WHERE workflow_run_id = ?",
                Long.class, runId);
    }

    private static TaskDefinition task(String key, String type, List<String> parents, Map<String, Object> input) {
        return new TaskDefinition(key, type, parents, input);
    }
}
