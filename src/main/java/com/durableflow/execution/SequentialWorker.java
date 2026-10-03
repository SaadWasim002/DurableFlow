package com.durableflow.execution;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.durableflow.execution.TaskHandlers.TaskFailure;

@Component
@ConditionalOnProperty(name = "durableflow.execution.enabled", havingValue = "true", matchIfMissing = true)
public class SequentialWorker {
    private static final Logger log = LoggerFactory.getLogger(SequentialWorker.class);
    private final ExecutionService execution;
    private final TaskHandlers handlers;

    public SequentialWorker(ExecutionService execution, TaskHandlers handlers) {
        this.execution = execution;
        this.handlers = handlers;
    }

    @Scheduled(fixedDelayString = "${durableflow.execution.poll-delay-ms:250}")
    public void poll() {
        try {
            execution.claimNext().ifPresent(this::execute);
        } catch (RuntimeException error) {
            // A persistence failure must not be mistaken for a handler failure.
            log.error("Worker persistence operation failed; inspect RUNNING tasks if an outcome was not saved", error);
        }
    }

    private void execute(ClaimedTask task) {
        log.atInfo().addKeyValue("runId", task.runId()).addKeyValue("taskId", task.taskId())
                .addKeyValue("attemptId", task.attemptId()).addKeyValue("taskKey", task.taskKey())
                .log("Task started");
        Map<String, Object> output;
        try {
            output = handlers.execute(task.type(), task.input());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.atWarn().addKeyValue("runId", task.runId()).addKeyValue("taskId", task.taskId())
                    .addKeyValue("attemptId", task.attemptId()).log("Task interrupted; attempt remains RUNNING");
            return;
        } catch (RuntimeException error) {
            String errorType = error instanceof TaskFailure failure ? failure.errorType()
                    : error.getClass().getSimpleName();
            boolean saved = execution.fail(task, errorType, error.getMessage());
            log.atWarn().addKeyValue("runId", task.runId()).addKeyValue("taskId", task.taskId())
                    .addKeyValue("attemptId", task.attemptId()).addKeyValue("errorType", errorType)
                    .addKeyValue("saved", saved).log("Task failed");
            return;
        }
        boolean saved = execution.succeed(task, output);
        log.atInfo().addKeyValue("runId", task.runId()).addKeyValue("taskId", task.taskId())
                .addKeyValue("attemptId", task.attemptId()).addKeyValue("saved", saved).log("Task succeeded");
    }
}
