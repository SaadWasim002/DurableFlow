package com.durableflow.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.durableflow.workflow.WorkflowExceptions.InvalidWorkflow;
import com.durableflow.workflow.WorkflowModels.TaskDefinition;

@Component
public class WorkflowValidator {
    private static final Set<String> TASK_TYPES = Set.of("echo", "sleep", "fail");

    public List<TaskDefinition> validateAndOrder(List<TaskDefinition> tasks) {
        Map<String, TaskDefinition> byId = new LinkedHashMap<>();
        for (TaskDefinition task : tasks) {
            if (byId.putIfAbsent(task.id(), task) != null) {
                throw new InvalidWorkflow("Duplicate task ID: " + task.id());
            }
            if (!TASK_TYPES.contains(task.type())) {
                throw new InvalidWorkflow("Unknown task type: " + task.type() + ". Supported types: echo, sleep, fail");
            }
        }

        List<TaskDefinition> roots = new ArrayList<>();
        Map<String, TaskDefinition> next = new HashMap<>();
        for (TaskDefinition task : tasks) {
            if (task.dependsOn().size() > 1) {
                throw new InvalidWorkflow("Only a single linear chain is supported; a task may have one parent");
            }
            if (task.dependsOn().isEmpty()) {
                roots.add(task);
                continue;
            }
            String parent = task.dependsOn().getFirst();
            if (task.id().equals(parent)) {
                throw new InvalidWorkflow("Task cannot depend on itself: " + task.id());
            }
            if (!byId.containsKey(parent)) {
                throw new InvalidWorkflow("Missing dependency: " + parent);
            }
            if (next.putIfAbsent(parent, task) != null) {
                throw new InvalidWorkflow("Only a single linear chain is supported; branching is not yet supported");
            }
        }
        if (roots.size() != 1) {
            throw new InvalidWorkflow("Workflow must have exactly one root and no cycles");
        }

        List<TaskDefinition> ordered = new ArrayList<>();
        TaskDefinition current = roots.getFirst();
        while (current != null && ordered.size() < tasks.size()) {
            ordered.add(current);
            current = next.get(current.id());
        }
        if (current != null || ordered.size() != tasks.size()) {
            throw new InvalidWorkflow("Workflow contains a cycle or disconnected tasks");
        }

        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            positions.put(ordered.get(i).id(), i);
        }
        for (int i = 0; i < ordered.size(); i++) {
            validateReferences(ordered.get(i).input(), i, positions);
        }
        return List.copyOf(ordered);
    }

    private void validateReferences(Map<String, Object> input, int position, Map<String, Integer> positions) {
        ArrayDeque<Object> pending = new ArrayDeque<>();
        pending.add(input);
        while (!pending.isEmpty()) {
            Object value = pending.removeFirst();
            if (value instanceof Map<?, ?> object) {
                if (object.containsKey("$ref")) {
                    if (object.size() != 1 || !(object.get("$ref") instanceof String reference)) {
                        throw new InvalidWorkflow("A reference must be an object containing only a string $ref");
                    }
                    if (reference.equals("run.input")) {
                        continue;
                    }
                    if (!reference.endsWith(".output")) {
                        throw new InvalidWorkflow("References must use run.input or <taskId>.output");
                    }
                    String taskId = reference.substring(0, reference.length() - ".output".length());
                    Integer target = positions.get(taskId);
                    if (target == null || target >= position) {
                        throw new InvalidWorkflow("Output reference must target an upstream task: " + reference);
                    }
                } else {
                    object.values().stream().filter(v -> v != null).forEach(pending::addLast);
                }
            } else if (value instanceof List<?> list) {
                list.stream().filter(v -> v != null).forEach(pending::addLast);
            }
        }
    }
}
