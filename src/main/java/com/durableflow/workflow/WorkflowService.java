package com.durableflow.workflow;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.durableflow.workflow.WorkflowExceptions.Conflict;
import com.durableflow.workflow.WorkflowExceptions.NotFound;
import com.durableflow.workflow.WorkflowModels.AttemptResponse;
import com.durableflow.workflow.WorkflowModels.CreateRunRequest;
import com.durableflow.workflow.WorkflowModels.CreateWorkflowRequest;
import com.durableflow.workflow.WorkflowModels.RunResponse;
import com.durableflow.workflow.WorkflowModels.TaskDefinition;
import com.durableflow.workflow.WorkflowModels.TaskResponse;
import com.durableflow.workflow.WorkflowModels.WorkflowResponse;

@Service
public class WorkflowService {
    private final WorkflowRepository repository;
    private final WorkflowValidator validator;

    public WorkflowService(WorkflowRepository repository, WorkflowValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    @Transactional
    public WorkflowResponse createWorkflow(CreateWorkflowRequest request) {
        List<TaskDefinition> tasks = validator.validateAndOrder(request.tasks());
        try {
            return repository.insertDefinition(request.name().strip(), request.version(), tasks);
        } catch (DuplicateKeyException exception) {
            throw new Conflict("A workflow with this name and version already exists");
        }
    }

    @Transactional(readOnly = true)
    public WorkflowResponse getWorkflow(UUID id) {
        return repository.findDefinition(id).orElseThrow(() -> new NotFound("Workflow not found: " + id));
    }

    @Transactional
    public RunResponse createRun(UUID workflowId, CreateRunRequest request) {
        WorkflowResponse definition = getWorkflow(workflowId);
        var run = repository.insertRun(workflowId, request.input());
        repository.insertInitialEvent(run.id(), null, "QUEUED", "Run created");
        for (TaskDefinition task : definition.tasks()) {
            String status = task.dependsOn().isEmpty() ? "READY" : "PENDING";
            UUID taskId = repository.insertTask(run.id(), task, status);
            repository.insertInitialEvent(run.id(), taskId, status, "Task initialized");
        }
        return RunResponse.from(run, repository.findTasks(run.id()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RunResponse getRun(UUID id) {
        var run = repository.findRun(id).orElseThrow(() -> new NotFound("Run not found: " + id));
        return RunResponse.from(run, repository.findTasks(id));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TaskResponse> getTasks(UUID runId) {
        return getRun(runId).tasks();
    }

    @Transactional(readOnly = true)
    public List<AttemptResponse> getAttempts(UUID taskId) {
        if (!repository.taskExists(taskId)) {
            throw new NotFound("Task not found: " + taskId);
        }
        return repository.findAttempts(taskId);
    }
}
