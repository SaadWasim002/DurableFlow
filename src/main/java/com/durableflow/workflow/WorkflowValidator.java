package com.durableflow.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
        if (tasks.isEmpty()) {
            throw new InvalidWorkflow("Workflow must contain at least one task");
        }
        Map<String, TaskDefinition> byId = new LinkedHashMap<>();
        for (TaskDefinition task : tasks) {
            if (byId.putIfAbsent(task.id(), task) != null) {
                throw new InvalidWorkflow("Duplicate task ID: " + task.id());
            }
            if (!TASK_TYPES.contains(task.type())) {
                throw new InvalidWorkflow("Unknown task type: " + task.type() + ". Supported types: echo, sleep, fail");
            }
        }

        Map<String, Integer> remainingParents = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        for (TaskDefinition task : tasks) {
            Set<String> seenParents = new HashSet<>();
            for (String parent : task.dependsOn()) {
                if (!seenParents.add(parent)) {
                    throw new InvalidWorkflow("Duplicate dependency on task " + task.id() + ": " + parent);
                }
                if (task.id().equals(parent)) {
                    throw new InvalidWorkflow("Task cannot depend on itself: " + task.id());
                }
                if (!byId.containsKey(parent)) {
                    throw new InvalidWorkflow("Missing dependency: " + parent);
                }
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(task.id());
            }
            remainingParents.put(task.id(), task.dependsOn().size());
        }
        // Kahn's algorithm: remove tasks whose parents have all been ordered.
        ArrayDeque<String> ready = new ArrayDeque<>();
        tasks.stream().filter(task -> task.dependsOn().isEmpty()).forEach(task -> ready.addLast(task.id()));
        List<TaskDefinition> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            ordered.add(byId.get(id));
            for (String child : children.getOrDefault(id, List.of())) {
                int remaining = remainingParents.compute(child, (key, count) -> count - 1);
                if (remaining == 0) {
                    ready.addLast(child);
                }
            }
        }
        if (ordered.size() != tasks.size()) {
            throw new InvalidWorkflow("Workflow contains a cycle");
        }
        Map<String, Set<String>> ancestors = new HashMap<>();
        for (TaskDefinition task : ordered) {
            Set<String> upstream = new HashSet<>();
            for (String parent : task.dependsOn()) {
                upstream.add(parent);
                upstream.addAll(ancestors.get(parent));
            }
            ancestors.put(task.id(), upstream);
            validateReferences(task.input(), upstream);
        }
        return List.copyOf(ordered);
    }

    private void validateReferences(Map<String, Object> input, Set<String> ancestors) {
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
                    if (!ancestors.contains(taskId)) {
                        throw new InvalidWorkflow("Output reference must target a dependency ancestor: " + reference);
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
