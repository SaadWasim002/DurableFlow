package com.durableflow.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class WorkflowModels {
    private WorkflowModels() {
    }

    public record CreateWorkflowRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Positive Integer version,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid TaskDefinition> tasks) {
    }

    public record TaskDefinition(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{0,199}") String id,
            @NotBlank String type,
            List<@NotBlank String> dependsOn,
            Map<String, Object> input) {
        public TaskDefinition {
            dependsOn = dependsOn == null ? List.of() : dependsOn;
            input = input == null ? Map.of() : input;
        }
    }

    public record CreateRunRequest(Map<String, Object> input) {
        public CreateRunRequest {
            input = input == null ? Map.of() : input;
        }
    }

    public record WorkflowResponse(UUID id, String name, int version,
                                   List<TaskDefinition> tasks, Instant createdAt) {
    }

    public record RunRecord(UUID id, UUID workflowId, String status, Map<String, Object> input,
                            Instant createdAt, Instant startedAt, Instant finishedAt) {
    }

    public record RunResponse(UUID id, UUID workflowId, String status, Map<String, Object> input,
                              Instant createdAt, Instant startedAt, Instant finishedAt,
                              List<TaskResponse> tasks) {
        public static RunResponse from(RunRecord run, List<TaskResponse> tasks) {
            return new RunResponse(run.id(), run.workflowId(), run.status(), run.input(),
                    run.createdAt(), run.startedAt(), run.finishedAt(), tasks);
        }
    }

    public record TaskResponse(UUID id, UUID runId, String taskKey, String type,
                               List<String> dependsOn, String status, Map<String, Object> input,
                               Object output, Instant createdAt, Instant startedAt, Instant finishedAt) {
    }

    public record AttemptResponse(UUID id, UUID taskId, int attemptNumber, String status,
                                  Map<String, Object> input, Object output, String errorType,
                                  String errorMessage, Instant startedAt, Instant finishedAt) {
    }
}
