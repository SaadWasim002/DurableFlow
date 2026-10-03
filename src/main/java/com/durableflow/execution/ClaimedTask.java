package com.durableflow.execution;

import java.util.Map;
import java.util.UUID;

public record ClaimedTask(UUID runId, UUID taskId, UUID attemptId, String taskKey,
                          String type, Map<String, Object> input) {
}
