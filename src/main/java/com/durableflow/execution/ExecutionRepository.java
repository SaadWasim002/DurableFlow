package com.durableflow.execution;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** All mutations are called inside ExecutionService transactions holding the run lock. */
@Repository
public class ExecutionRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public ExecutionRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<UUID> lockRunnableRun() {
        return jdbc.query("""
                SELECT r.id FROM workflow_runs r
                WHERE r.status IN ('QUEUED', 'RUNNING')
                  AND EXISTS (SELECT 1 FROM task_runs t
                              WHERE t.workflow_run_id = r.id AND t.status = 'READY')
                  AND NOT EXISTS (SELECT 1 FROM task_runs t
                                  WHERE t.workflow_run_id = r.id AND t.status = 'RUNNING')
                ORDER BY r.created_at, r.id
                LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                """, (rs, row) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }

    public String lockRun(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM workflow_runs WHERE id = ? FOR UPDATE",
                String.class, runId);
    }

    public void startRun(UUID runId) {
        requireOne(jdbc.update("""
                UPDATE workflow_runs SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'QUEUED'
                """, runId));
        event(runId, null, null, "QUEUED", "RUNNING", "Worker started workflow");
    }

    public void startTask(ClaimedTask task) {
        requireOne(jdbc.update("""
                UPDATE task_runs SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP
                WHERE id = ? AND workflow_run_id = ? AND status = 'READY'
                """, task.taskId(), task.runId()));
        jdbc.update("""
                INSERT INTO task_attempts (id, task_run_id, attempt_number, input)
                VALUES (?, ?, 1, ?::jsonb)
                """, task.attemptId(), task.taskId(), json.writeValueAsString(task.input()));
        event(task.runId(), task.taskId(), task.attemptId(), "READY", "RUNNING", "Worker claimed task");
    }

    public boolean attemptIsRunning(ClaimedTask task) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM task_runs t JOIN task_attempts a ON a.task_run_id = t.id
                    WHERE t.id = ? AND t.workflow_run_id = ? AND t.status = 'RUNNING'
                      AND a.id = ? AND a.status = 'RUNNING')
                """, Boolean.class, task.taskId(), task.runId(), task.attemptId()));
    }

    public void finishTask(ClaimedTask task, Object output, String errorType, String errorMessage) {
        String status = errorType == null ? "SUCCEEDED" : "FAILED";
        String outputJson = output == null ? null : json.writeValueAsString(output);
        requireOne(jdbc.update("""
                UPDATE task_attempts
                SET status = ?, output = ?::jsonb, error_type = ?, error_message = ?,
                    finished_at = CURRENT_TIMESTAMP
                WHERE id = ? AND task_run_id = ? AND status = 'RUNNING'
                """, status, outputJson, errorType, errorMessage, task.attemptId(), task.taskId()));
        requireOne(jdbc.update("""
                UPDATE task_runs SET status = ?, output = ?::jsonb, finished_at = CURRENT_TIMESTAMP
                WHERE id = ? AND workflow_run_id = ? AND status = 'RUNNING'
                """, status, outputJson, task.taskId(), task.runId()));
        event(task.runId(), task.taskId(), task.attemptId(), "RUNNING", status,
                errorType == null ? "Handler completed" : errorType + ": " + errorMessage);
    }

    public void readyTask(UUID runId, UUID taskId) {
        requireOne(jdbc.update("UPDATE task_runs SET status = 'READY' WHERE id = ? AND status = 'PENDING'",
                taskId));
        event(runId, taskId, null, "PENDING", "READY", "Dependency succeeded");
    }

    public void skipTask(UUID runId, UUID taskId, String oldStatus) {
        requireOne(jdbc.update("""
                UPDATE task_runs SET status = 'SKIPPED', finished_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = ?
                """, taskId, oldStatus));
        event(runId, taskId, null, oldStatus, "SKIPPED", "Workflow stopped after task failure");
    }

    public void finishRun(UUID runId, String status) {
        requireOne(jdbc.update("""
                UPDATE workflow_runs SET status = ?, finished_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                """, status, runId));
        event(runId, null, null, "RUNNING", status,
                status.equals("SUCCEEDED") ? "All tasks succeeded" : "Workflow stopped after task failure");
    }

    private void event(UUID runId, UUID taskId, UUID attemptId, String from, String to, String reason) {
        jdbc.update("""
                INSERT INTO execution_events
                    (id, workflow_run_id, task_run_id, task_attempt_id, from_status, to_status, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), runId, taskId, attemptId, from, to, reason);
    }

    private static void requireOne(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("Execution state changed unexpectedly");
        }
    }
}
