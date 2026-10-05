package com.durableflow.execution;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import com.durableflow.workflow.WorkflowModels.AttemptResponse;
import com.durableflow.workflow.WorkflowModels.CreateRunRequest;
import com.durableflow.workflow.WorkflowModels.CreateWorkflowRequest;
import com.durableflow.workflow.WorkflowModels.RunResponse;
import com.durableflow.workflow.WorkflowModels.TaskDefinition;
import com.durableflow.workflow.WorkflowModels.TaskResponse;
import com.durableflow.workflow.WorkflowModels.WorkflowResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP requests with the actual scheduler and worker enabled. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"durableflow.execution.enabled=true", "durableflow.execution.poll-delay-ms=10"},
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class SequentialExecutionApiTests {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @LocalServerPort int port;
    @Autowired JsonMapper json;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void executesAndReadsThreeTaskResultsThroughTheExistingApis() throws Exception {
        var created = submit(List.of(
                task("prepare", "echo", List.of(), Map.of("$ref", "run.input")),
                task("format", "echo", List.of("prepare"), Map.of("previous", Map.of("$ref", "prepare.output"))),
                task("save", "echo", List.of("format"), Map.of("$ref", "format.output"))));
        assertThat(created.status()).isEqualTo("QUEUED");
        var run = awaitFinished(created.id());
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.tasks()).extracting(TaskResponse::status).containsOnly("SUCCEEDED");
        assertThat(run.tasks().getLast().output()).isEqualTo(Map.of("previous", Map.of("report", "demo")));
        var tasks = json.readValue(send("GET", "/runs/" + run.id() + "/tasks", null, 200), TaskResponse[].class);
        assertThat(tasks).containsExactlyElementsOf(run.tasks());
        for (var task : tasks) {
            var attempts = json.readValue(send("GET", "/tasks/" + task.id() + "/attempts", null, 200), AttemptResponse[].class);
            assertThat(attempts).hasSize(1);
            assertThat(attempts[0].status()).isEqualTo("SUCCEEDED");
            assertThat(attempts[0].output()).isEqualTo(task.output());
            assertThat(attempts[0].finishedAt()).isNotNull();
        }
        var definition = json.readValue(send("GET", "/workflows/" + run.workflowId(), null, 200), WorkflowResponse.class);
        assertThat(definition.tasks().getFirst().input()).isEqualTo(Map.of("$ref", "run.input"));
    }

    @Test
    void recordsHandlerFailureSkipsDescendantsAndContinuesProcessingOtherRuns() throws Exception {
        var created = submit(List.of(task("first", "echo", List.of(), Map.of()),
                task("second", "fail", List.of("first"), Map.of("message", "Demo provider failed")),
                task("third", "echo", List.of("second"), Map.of())));
        var run = awaitFinished(created.id());
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.tasks()).extracting(TaskResponse::status).containsExactly("SUCCEEDED", "FAILED", "SKIPPED");
        var attempts = json.readValue(send("GET", "/tasks/" + run.tasks().get(1).id() + "/attempts", null, 200), AttemptResponse[].class);
        assertThat(attempts[0].errorType()).isEqualTo("DEMO_FAILURE");
        assertThat(attempts[0].errorMessage()).isEqualTo("Demo provider failed");
        assertThat(send("GET", "/tasks/" + run.tasks().getLast().id() + "/attempts", null, 200)).isEqualTo("[]");
        var next = submit(List.of(task("healthy", "echo", List.of(), Map.of())));
        assertThat(awaitFinished(next.id()).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void oneWorkerExecutesSleepAndOtherRunsWithoutOverlappingAttempts() throws Exception {
        var first = submit(List.of(task("wait", "sleep", List.of(), Map.of("millis", 200))));
        var second = submit(List.of(task("echo", "echo", List.of(), Map.of("message", "next"))));
        var firstDone = awaitFinished(first.id());
        var secondDone = awaitFinished(second.id());
        assertThat(firstDone.status()).isEqualTo("SUCCEEDED");
        assertThat(secondDone.status()).isEqualTo("SUCCEEDED");
        assertThat(secondDone.tasks().getFirst().startedAt()).isAfterOrEqualTo(firstDone.tasks().getFirst().finishedAt());
    }

    @Test
    void invalidResolvedHandlerInputFailsTheAttemptInsteadOfTheSubmission() throws Exception {
        var created = submit(List.of(task("wait", "sleep", List.of(), Map.of("millis", "invalid"))));
        var run = awaitFinished(created.id());
        assertThat(run.status()).isEqualTo("FAILED");
        var attempts = json.readValue(send("GET", "/tasks/" + run.tasks().getFirst().id() + "/attempts", null, 200), AttemptResponse[].class);
        assertThat(attempts[0].input()).containsEntry("millis", "invalid");
        assertThat(attempts[0].errorType()).isEqualTo("INVALID_INPUT");
    }

    @Test
    void executesAnUnorderedDagAndJoinsSavedBranchOutputsThroughHttp() throws Exception {
        var created = submit(List.of(
                task("join", "echo", List.of("left", "right"), Map.of(
                        "left", Map.of("$ref", "left.output"), "right", Map.of("$ref", "right.output"),
                        "original", Map.of("$ref", "root.output"))),
                task("left", "echo", List.of("root"), Map.of("value", "left")),
                task("root", "echo", List.of(), Map.of("$ref", "run.input")),
                task("right", "sleep", List.of("root"), Map.of("millis", 0, "value", "right"))));
        assertThat(created.tasks()).extracting(TaskResponse::taskKey).containsExactly("root", "left", "right", "join");
        var run = awaitFinished(created.id());
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        var join = run.tasks().getLast();
        assertThat(join.output()).isEqualTo(Map.of("left", Map.of("value", "left"),
                "right", Map.of("millis", 0, "value", "right"), "original", Map.of("report", "demo")));
        assertThat(join.startedAt()).isAfterOrEqualTo(run.tasks().get(1).finishedAt());
        assertThat(join.startedAt()).isAfterOrEqualTo(run.tasks().get(2).finishedAt());
        var attempts = json.readValue(send("GET", "/tasks/" + join.id() + "/attempts", null, 200), AttemptResponse[].class);
        assertThat(attempts).hasSize(1);
        assertThat(attempts[0].input()).isEqualTo(join.output());
    }

    @Test
    void failingDagBranchStopsTheRunAndLeavesTheJoinUnstarted() throws Exception {
        var created = submit(List.of(task("root", "echo", List.of(), Map.of()),
                task("left", "fail", List.of("root"), Map.of("message", "Left branch failed")),
                task("right", "echo", List.of("root"), Map.of()),
                task("join", "echo", List.of("left", "right"), Map.of())));
        var run = awaitFinished(created.id());
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.tasks()).extracting(TaskResponse::status).containsExactly("SUCCEEDED", "FAILED", "SKIPPED", "SKIPPED");
        assertThat(send("GET", "/tasks/" + run.tasks().getLast().id() + "/attempts", null, 200)).isEqualTo("[]");
        assertThat(run.tasks().getLast().startedAt()).isNull();
    }

    private RunResponse submit(List<TaskDefinition> tasks) throws Exception {
        var definition = json.readValue(send("POST", "/workflows",
                new CreateWorkflowRequest("worker-api-" + UUID.randomUUID(), 1, tasks), 201), WorkflowResponse.class);
        return json.readValue(send("POST", "/workflows/" + definition.id() + "/runs",
                new CreateRunRequest(Map.of("report", "demo")), 202), RunResponse.class);
    }

    private RunResponse awaitFinished(UUID id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        RunResponse run;
        do {
            run = json.readValue(send("GET", "/runs/" + id, null, 200), RunResponse.class);
            if (run.status().equals("SUCCEEDED") || run.status().equals("FAILED")) return run;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Run did not finish: " + run);
    }

    private String send(String method, String path, Object body, int expectedStatus) throws Exception {
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, publisher).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return response.body();
    }

    private static TaskDefinition task(String key, String type, List<String> parents, Map<String, Object> input) {
        return new TaskDefinition(key, type, parents, input);
    }
}
