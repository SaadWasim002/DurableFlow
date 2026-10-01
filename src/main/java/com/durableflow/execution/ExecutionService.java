package com.durableflow.execution;

import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.durableflow.workflow.WorkflowModels.TaskResponse;
import com.durableflow.workflow.WorkflowRepository;

/** Short claim/completion transactions; handlers run outside this service. */
@Service
public class ExecutionService {
    private final ExecutionRepository execution;
    private final WorkflowRepository workflows;
    private final InputResolver inputs;

    public ExecutionService(ExecutionRepository execution, WorkflowRepository workflows, InputResolver inputs) {
        this.execution = execution;
        this.workflows = workflows;
        this.inputs = inputs;
    }

    @Transactional
    public Optional<ClaimedTask> claimNext() {
        var candidate = execution.lockRunnableRun();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        UUID runId = candidate.get();
        var run = workflows.findRun(runId).orElseThrow();
        if (!run.status().equals("QUEUED") && !run.status().equals("RUNNING")) {
            return Optional.empty();
        }
        var tasks = workflows.findTasks(runId);
        // Recheck after obtaining the lock, since a competing claim may have committed.
        if (tasks.stream().anyMatch(t -> t.status().equals("RUNNING"))) {
            return Optional.empty();
        }
        var next = tasks.stream().filter(t -> t.status().equals("READY")).findFirst();
        if (next.isEmpty()) {
            return Optional.empty();
        }
        TaskResponse task = next.get();
        var outputs = new HashMap<String, Object>();
        tasks.stream().filter(t -> t.status().equals("SUCCEEDED"))
                .forEach(t -> outputs.put(t.taskKey(), t.output()));
        if (!outputs.keySet().containsAll(task.dependsOn())) {
            return Optional.empty();
        }
        var claimed = new ClaimedTask(runId, task.id(), UUID.randomUUID(), task.taskKey(), task.type(),
                inputs.resolve(task.input(), run.input(), outputs));
        if (run.status().equals("QUEUED")) {
            execution.startRun(runId);
        }
        execution.startTask(claimed);
        return Optional.of(claimed);
    }

    @Transactional
    public boolean succeed(ClaimedTask task, Object output) {
        if (!canComplete(task)) {
            return false;
        }
        execution.finishTask(task, output, null, null);
        var tasks = workflows.findTasks(task.runId());
        tasks.stream().filter(t -> t.status().equals("PENDING") && t.dependsOn().contains(task.taskKey()))
                .forEach(t -> execution.readyTask(task.runId(), t.id()));
        if (tasks.stream().allMatch(t -> t.status().equals("SUCCEEDED"))) {
            execution.finishRun(task.runId(), "SUCCEEDED");
        }
        return true;
    }

    @Transactional
    public boolean fail(ClaimedTask task, String errorType, String errorMessage) {
        if (!canComplete(task)) {
            return false;
        }
        execution.finishTask(task, null, errorType, errorMessage);
        workflows.findTasks(task.runId()).stream()
                .filter(t -> t.status().equals("PENDING") || t.status().equals("READY"))
                .forEach(t -> execution.skipTask(task.runId(), t.id(), t.status()));
        execution.finishRun(task.runId(), "FAILED");
        return true;
    }

    private boolean canComplete(ClaimedTask task) {
        return execution.lockRun(task.runId()).equals("RUNNING") && execution.attemptIsRunning(task);
    }
}
