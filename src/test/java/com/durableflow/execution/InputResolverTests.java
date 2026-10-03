package com.durableflow.execution;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputResolverTests {
    private final InputResolver resolver = new InputResolver();

    @Test
    void resolvesNestedReferencesAndNullsWithoutEvaluatingLiteralData() {
        var literal = Map.<String, Object>of("$ref", "literal.data");
        var input = Map.<String, Object>of("report", literal);
        var template = Map.<String, Object>of("items", Arrays.asList(null,
                Map.of("$ref", "first.output"), Map.of("$ref", "run.input")));
        var resolved = resolver.resolve(template, input, Map.of("first", input));
        assertThat(resolved).isEqualTo(Map.of("items", Arrays.asList(null, input, input)));
        assertThat(template.get("items")).isEqualTo(Arrays.asList(null,
                Map.of("$ref", "first.output"), Map.of("$ref", "run.input")));
    }

    @Test
    void supportsWholeObjectReferencesAndRejectsMissingOutputs() {
        assertThat(resolver.resolve(Map.of("$ref", "run.input"), Map.of("key", "value"), Map.of()))
                .containsEntry("key", "value");
        assertThatThrownBy(() -> resolver.resolve(Map.of("$ref", "missing.output"), Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
    }
}
