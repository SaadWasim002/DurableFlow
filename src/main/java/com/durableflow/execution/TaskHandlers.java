package com.durableflow.execution;

import java.util.Map;

import org.springframework.stereotype.Component;

/** Local demonstration handlers. Each handler receives resolved attempt input. */
@Component
public class TaskHandlers {
    private final Map<String, Handler> handlers = Map.of(
            "echo", input -> input,
            "sleep", this::sleep,
            "fail", this::fail);

    public Map<String, Object> execute(String type, Map<String, Object> input) throws InterruptedException {
        Handler handler = handlers.get(type);
        if (handler == null) {
            throw new TaskFailure("UNKNOWN_HANDLER", "No handler registered for type: " + type);
        }
        return handler.execute(input);
    }

    private Map<String, Object> sleep(Map<String, Object> input) throws InterruptedException {
        Object value = input.getOrDefault("millis", 1000);
        if (!(value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < 0 || ((Number) value).longValue() > 60_000) {
            throw new TaskFailure("INVALID_INPUT", "sleep input.millis must be an integer from 0 to 60000");
        }
        Thread.sleep(((Number) value).longValue());
        return input;
    }

    private Map<String, Object> fail(Map<String, Object> input) {
        Object message = input.getOrDefault("message", "Simulated task failure");
        if (!(message instanceof String text)) {
            throw new TaskFailure("INVALID_INPUT", "fail input.message must be a string");
        }
        throw new TaskFailure("DEMO_FAILURE", text);
    }

    @FunctionalInterface
    private interface Handler {
        Map<String, Object> execute(Map<String, Object> input) throws InterruptedException;
    }

    public static class TaskFailure extends RuntimeException {
        private final String errorType;

        public TaskFailure(String errorType, String message) {
            super(message);
            this.errorType = errorType;
        }

        public String errorType() {
            return errorType;
        }
    }
}
