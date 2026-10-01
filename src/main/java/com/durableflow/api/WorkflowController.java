package com.durableflow.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.durableflow.workflow.WorkflowModels.AttemptResponse;
import com.durableflow.workflow.WorkflowModels.CreateRunRequest;
import com.durableflow.workflow.WorkflowModels.CreateWorkflowRequest;
import com.durableflow.workflow.WorkflowModels.RunResponse;
import com.durableflow.workflow.WorkflowModels.TaskResponse;
import com.durableflow.workflow.WorkflowModels.WorkflowResponse;
import com.durableflow.workflow.WorkflowService;

@RestController
public class WorkflowController {
    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @PostMapping("/workflows")
    public ResponseEntity<WorkflowResponse> createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
        var workflow = service.createWorkflow(request);
        return ResponseEntity.created(URI.create("/workflows/" + workflow.id())).body(workflow);
    }

    @GetMapping("/workflows/{id}")
    public WorkflowResponse getWorkflow(@PathVariable UUID id) {
        return service.getWorkflow(id);
    }

    @PostMapping("/workflows/{id}/runs")
    public ResponseEntity<RunResponse> createRun(@PathVariable UUID id,
                                                @RequestBody(required = false) CreateRunRequest request) {
        var run = service.createRun(id, request == null ? new CreateRunRequest(Map.of()) : request);
        return ResponseEntity.accepted().location(URI.create("/runs/" + run.id())).body(run);
    }

    @GetMapping("/runs/{id}")
    public RunResponse getRun(@PathVariable UUID id) {
        return service.getRun(id);
    }

    @GetMapping("/runs/{id}/tasks")
    public List<TaskResponse> getTasks(@PathVariable UUID id) {
        return service.getTasks(id);
    }

    @GetMapping("/tasks/{id}/attempts")
    public List<AttemptResponse> getAttempts(@PathVariable UUID id) {
        return service.getAttempts(id);
    }
}
