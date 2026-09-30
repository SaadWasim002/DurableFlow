package com.durableflow.workflow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.durableflow.workflow.WorkflowModels.AttemptResponse;
import com.durableflow.workflow.WorkflowModels.RunRecord;
import com.durableflow.workflow.WorkflowModels.TaskDefinition;
import com.durableflow.workflow.WorkflowModels.TaskResponse;
import com.durableflow.workflow.WorkflowModels.WorkflowResponse;

@Repository
public class WorkflowRepository {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public WorkflowRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public WorkflowResponse insertDefinition(String name, int version, List<TaskDefinition> tasks) {
        return jdbc.queryForObject("""
                INSERT INTO workflow_definitions (id, name, version, definition)
                VALUES (?, ?, ?, ?::jsonb) RETURNING *
                """, (rs, row) -> mapDefinition(rs), UUID.randomUUID(), name, version,
                json.writeValueAsString(new StoredDefinition(tasks)));
    }

    public Optional<WorkflowResponse> findDefinition(UUID id) {
        return jdbc.query("SELECT * FROM workflow_definitions WHERE id = ?",
                (rs, row) -> mapDefinition(rs), id).stream().findFirst();
    }

    public RunRecord insertRun(UUID workflowId, Map<String, Object> input) {
        return jdbc.queryForObject("""
                INSERT INTO workflow_runs (id, workflow_definition_id, input)
                VALUES (?, ?, ?::jsonb) RETURNING *
                """, (rs, row) -> mapRun(rs), UUID.randomUUID(), workflowId, json.writeValueAsString(input));
    }

    public Optional<RunRecord> findRun(UUID id) {
        return jdbc.query("SELECT * FROM workflow_runs WHERE id = ?",
                (rs, row) -> mapRun(rs), id).stream().findFirst();
    }

    public UUID insertTask(UUID runId, TaskDefinition task, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task_runs (id, workflow_run_id, task_key, task_type, status, input)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, id, runId, task.id(), task.type(), status, json.writeValueAsString(task.input()));
        return id;
    }

    public void insertInitialEvent(UUID runId, UUID taskId, String status, String reason) {
        jdbc.update("""
                INSERT INTO execution_events (id, workflow_run_id, task_run_id, to_status, reason)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), runId, taskId, status, reason);
    }

    public List<TaskResponse> findTasks(UUID runId) {
        return jdbc.query("""
                SELECT t.*, spec.task_definition AS specification
                FROM task_runs t
                JOIN workflow_runs r ON r.id = t.workflow_run_id
                JOIN workflow_definitions d ON d.id = r.workflow_definition_id
                JOIN LATERAL jsonb_array_elements(d.definition -> 'tasks')
                    WITH ORDINALITY spec(task_definition, position)
                    ON spec.task_definition ->> 'id' = t.task_key
                WHERE t.workflow_run_id = ?
                ORDER BY spec.position
                """, (rs, row) -> new TaskResponse(
                rs.getObject("id", UUID.class), rs.getObject("workflow_run_id", UUID.class),
                rs.getString("task_key"), rs.getString("task_type"),
                json.readValue(rs.getString("specification"), TaskDefinition.class).dependsOn(),
                rs.getString("status"), readInput(rs), readOutput(rs),
                instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at")), runId);
    }

    public boolean taskExists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM task_runs WHERE id = ?)", Boolean.class, id));
    }

    public List<AttemptResponse> findAttempts(UUID taskId) {
        return jdbc.query("""
                SELECT * FROM task_attempts WHERE task_run_id = ? ORDER BY attempt_number
                """, (rs, row) -> new AttemptResponse(
                rs.getObject("id", UUID.class), rs.getObject("task_run_id", UUID.class),
                rs.getInt("attempt_number"), rs.getString("status"), readInput(rs), readOutput(rs),
                rs.getString("error_type"), rs.getString("error_message"),
                instant(rs, "started_at"), instant(rs, "finished_at")), taskId);
    }

    private WorkflowResponse mapDefinition(ResultSet rs) throws SQLException {
        return new WorkflowResponse(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getInt("version"), json.readValue(rs.getString("definition"), StoredDefinition.class).tasks(),
                instant(rs, "created_at"));
    }

    private RunRecord mapRun(ResultSet rs) throws SQLException {
        return new RunRecord(rs.getObject("id", UUID.class),
                rs.getObject("workflow_definition_id", UUID.class), rs.getString("status"),
                readInput(rs), instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private Map<String, Object> readInput(ResultSet rs) throws SQLException {
        return json.readValue(rs.getString("input"), OBJECT_MAP);
    }

    private Object readOutput(ResultSet rs) throws SQLException {
        String value = rs.getString("output");
        return value == null ? null : json.readValue(value, Object.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record StoredDefinition(List<TaskDefinition> tasks) {
    }
}
