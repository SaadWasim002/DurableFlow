package com.durableflow.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Resolves validated references using this run's persisted successful outputs. */
@Component
public class InputResolver {
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolve(Map<String, Object> template, Map<String, Object> runInput,
                                       Map<String, Object> outputs) {
        Object resolved = resolveValue(template, runInput, outputs);
        if (!(resolved instanceof Map<?, ?>)) {
            throw new IllegalStateException("Resolved task input must be a JSON object");
        }
        return (Map<String, Object>) resolved;
    }

    private Object resolveValue(Object value, Map<String, Object> runInput, Map<String, Object> outputs) {
        if (value instanceof Map<?, ?> object) {
            if (object.containsKey("$ref")) {
                String reference = (String) object.get("$ref");
                if (reference.equals("run.input")) {
                    return copy(runInput);
                }
                String taskKey = reference.substring(0, reference.length() - ".output".length());
                if (!outputs.containsKey(taskKey)) {
                    throw new IllegalStateException("Upstream output is unavailable: " + taskKey);
                }
                // Copy literal output; reference-like objects in data are not evaluated again.
                return copy(outputs.get(taskKey));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            object.forEach((key, item) -> result.put((String) key, resolveValue(item, runInput, outputs)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> resolveValue(item, runInput, outputs)).toList();
        }
        return value;
    }

    private Object copy(Object value) {
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> result = new LinkedHashMap<>();
            object.forEach((key, item) -> result.put((String) key, copy(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(copy(item)));
            return result;
        }
        return value;
    }
}
