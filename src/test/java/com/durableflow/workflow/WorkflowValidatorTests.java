package com.durableflow.workflow;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.durableflow.workflow.WorkflowExceptions.InvalidWorkflow;
import com.durableflow.workflow.WorkflowModels.TaskDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowValidatorTests {
    private final WorkflowValidator validator = new WorkflowValidator();

    @Test
    void ordersAChainAndAcceptsReferencesToRunInputAndUpstreamOutputs() {
        TaskDefinition first = new TaskDefinition("first", "echo", null, Map.of("$ref", "run.input"));
        TaskDefinition second = new TaskDefinition("second", "echo", List.of("first"),
                Map.of("values", List.of(Map.of("$ref", "first.output"))));

        assertThat(validator.validateAndOrder(List.of(second, first))).containsExactly(first, second);
    }

    @ParameterizedTest
    @MethodSource("invalidGraphs")
    void rejectsInvalidGraphs(List<TaskDefinition> tasks) {
        assertThatThrownBy(() -> validator.validateAndOrder(tasks)).isInstanceOf(InvalidWorkflow.class);
    }

    static Stream<List<TaskDefinition>> invalidGraphs() {
        return Stream.of(
                List.of(task("a"), task("a")),
                List.of(task("a", "missing")),
                List.of(task("a", "a")),
                List.of(task("a", "b"), task("b", "a")),
                List.of(task("a"), task("b")),
                List.of(task("a"), task("b", "a"), task("c", "a")),
                List.of(task("a"), new TaskDefinition("b", "echo", List.of("a", "c"), Map.of())),
                List.of(task("root"), task("a", "b"), task("b", "a")),
                List.of(new TaskDefinition("a", "unknown", null, null)));
    }

    @ParameterizedTest
    @MethodSource("invalidReferences")
    void rejectsMalformedMissingAndForwardReferences(Map<String, Object> input) {
        assertThatThrownBy(() -> validator.validateAndOrder(
                List.of(new TaskDefinition("a", "echo", null, input), task("b", "a"))))
                .isInstanceOf(InvalidWorkflow.class);
    }

    static Stream<Map<String, Object>> invalidReferences() {
        return Stream.of(Map.of("$ref", "a.output"), Map.of("$ref", "b.output"),
                Map.of("$ref", "missing.output"), Map.of("$ref", "a.input"),
                Map.of("$ref", 123), Map.of("$ref", "run.input", "extra", true));
    }

    private static TaskDefinition task(String id, String... parents) {
        return new TaskDefinition(id, "echo", List.of(parents), Map.of());
    }
}
