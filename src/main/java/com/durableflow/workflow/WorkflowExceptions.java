package com.durableflow.workflow;

public final class WorkflowExceptions {
    private WorkflowExceptions() {
    }

    public static class InvalidWorkflow extends RuntimeException {
        public InvalidWorkflow(String message) {
            super(message);
        }
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }
}
