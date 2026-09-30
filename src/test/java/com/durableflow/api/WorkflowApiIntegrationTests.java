package com.durableflow.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import com.durableflow.workflow.WorkflowModels.CreateRunRequest;
import com.durableflow.workflow.WorkflowModels.CreateWorkflowRequest;
import com.durableflow.workflow.WorkflowModels.RunResponse;
import com.durableflow.workflow.WorkflowModels.TaskDefinition;
import com.durableflow.workflow.WorkflowModels.TaskResponse;
import com.durableflow.workflow.WorkflowModels.WorkflowResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class WorkflowApiIntegrationTests {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void createsAndReadsAWorkflowAndItsQueuedRunThroughEveryEndpoint() throws Exception {
        var workflow = createWorkflow();
        var workflowRead = send("GET", "/workflows/" + workflow.id(), null);
        assertThat(workflowRead.statusCode()).isEqualTo(200);
        assertThat(json.readValue(workflowRead.body(), WorkflowResponse.class)).isEqualTo(workflow);

        var submitted = send("POST", "/workflows/" + workflow.id() + "/runs",
                new CreateRunRequest(Map.of("reportName", "demo")));
        assertThat(submitted.statusCode()).isEqualTo(202);
        var run = json.readValue(submitted.body(), RunResponse.class);
        assertThat(submitted.headers().firstValue("Location")).contains("/runs/" + run.id());
        assertThat(run.status()).isEqualTo("QUEUED");
        assertThat(run.input()).containsEntry("reportName", "demo");
        assertThat(run.tasks()).extracting(TaskResponse::taskKey).containsExactly("prepare", "format", "save");
        assertThat(run.tasks()).extracting(TaskResponse::status).containsExactly("READY", "PENDING", "PENDING");
        assertThat(run.tasks()).allSatisfy(task -> {
            assertThat(task.startedAt()).isNull();
            assertThat(task.output()).isNull();
        });

        var read = send("GET", "/runs/" + run.id(), null);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(json.readValue(read.body(), RunResponse.class)).isEqualTo(run);
        var tasks = send("GET", "/runs/" + run.id() + "/tasks", null);
        assertThat(tasks.statusCode()).isEqualTo(200);
        assertThat(json.readValue(tasks.body(), TaskResponse[].class)).containsExactlyElementsOf(run.tasks());
        var attempts = send("GET", "/tasks/" + run.tasks().getFirst().id() + "/attempts", null);
        assertThat(attempts.statusCode()).isEqualTo(200);
        assertThat(attempts.body()).isEqualTo("[]");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM execution_events WHERE workflow_run_id = ?", Long.class, run.id()))
                .isEqualTo(4L);
    }

    @Test
    void acceptsAnOmittedRunBodyAndCreatesANewRunForEachRequest() throws Exception {
        var workflow = createWorkflow();
        var first = send("POST", "/workflows/" + workflow.id() + "/runs", null);
        var second = send("POST", "/workflows/" + workflow.id() + "/runs", Map.of());
        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(202);
        var firstRun = json.readValue(first.body(), RunResponse.class);
        var secondRun = json.readValue(second.body(), RunResponse.class);
        assertThat(firstRun.input()).isEmpty();
        assertThat(secondRun.id()).isNotEqualTo(firstRun.id());
    }

    @Test
    void preventsDuplicateVersionsEvenWhenRequestsArriveConcurrently() throws Exception {
        var request = definition("concurrent-" + UUID.randomUUID());
        Callable<HttpResponse<String>> submit = () -> send("POST", "/workflows", request);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var responses = executor.invokeAll(List.of(submit, submit));
            var first = responses.get(0).get();
            var second = responses.get(1).get();
            assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(201, 409);
            assertProblem(first.statusCode() == 409 ? first : second, 409);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM workflow_definitions WHERE name = ?", Long.class, request.name()))
                .isEqualTo(1L);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void returnsBadRequestWithoutSavingInvalidDefinitions(String body) throws Exception {
        long countBefore = jdbc.queryForObject("SELECT count(*) FROM workflow_definitions", Long.class);
        assertProblem(send("POST", "/workflows", body), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_definitions", Long.class))
                .isEqualTo(countBefore);
    }

    static Stream<String> invalidBodies() {
        return Stream.of(
                "{}",
                "{\"name\":\"demo\",\"version\":0,\"tasks\":[{\"id\":\"a\",\"type\":\"echo\"}]}",
                "{\"name\":\"demo\",\"version\":1.5,\"tasks\":[{\"id\":\"a\",\"type\":\"echo\"}]}",
                "{\"name\":\"demo\",\"version\":1,\"tasks\":[]}",
                "{\"name\":\"demo\",\"version\":1,\"tasks\":[null]}",
                "{\"name\":\"demo\",\"version\":1,\"tasks\":[{\"id\":\"bad id\",\"type\":\"echo\"}]}",
                "{\"name\":\"demo\",\"version\":1,\"tasks\":[{\"id\":\"a\",\"type\":\"unknown\"}]}",
                "{\"name\":\"demo\",\"version\":1,\"tasks\":[{\"id\":\"a\",\"type\":\"echo\",\"depends_on\":[]}]}",
                "{\"name\":\"demo\",\"version\":1,\"tasks\":[{\"id\":\"a\",\"type\":\"echo\",\"dependsOn\":[\"b\"]},{\"id\":\"b\",\"type\":\"echo\",\"dependsOn\":[\"a\"]}]}",
                "not json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/workflows/", "/runs/", "/tasks/"})
    void returnsNotFoundForUnknownResources(String prefix) throws Exception {
        String path = prefix + UUID.randomUUID();
        if (prefix.equals("/tasks/")) {
            path += "/attempts";
        }
        assertProblem(send("GET", path, null), 404);
    }

    @Test
    void distinguishesMissingRunsFromEmptyAttemptsAndRejectsMalformedIdentifiers() throws Exception {
        assertProblem(send("GET", "/runs/" + UUID.randomUUID() + "/tasks", null), 404);
        assertProblem(send("POST", "/workflows/" + UUID.randomUUID() + "/runs", Map.of()), 404);
        assertProblem(send("GET", "/runs/not-a-uuid", null), 400);
    }

    private WorkflowResponse createWorkflow() throws Exception {
        var response = send("POST", "/workflows", definition("api-" + UUID.randomUUID()));
        assertThat(response.statusCode()).isEqualTo(201);
        var workflow = json.readValue(response.body(), WorkflowResponse.class);
        assertThat(response.headers().firstValue("Location")).contains("/workflows/" + workflow.id());
        return workflow;
    }

    private static CreateWorkflowRequest definition(String name) {
        return new CreateWorkflowRequest(name, 1, List.of(
                new TaskDefinition("save", "echo", List.of("format"), Map.of("$ref", "format.output")),
                new TaskDefinition("prepare", "echo", null, Map.of("message", "hello")),
                new TaskDefinition("format", "echo", List.of("prepare"), Map.of("$ref", "prepare.output"))));
    }

    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        String content = body instanceof String raw ? raw : body == null ? null : json.writeValueAsString(body);
        var publisher = content == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(content);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, publisher).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(json.readValue(response.body(), Map.class).get("status")).isEqualTo(status);
    }
}
