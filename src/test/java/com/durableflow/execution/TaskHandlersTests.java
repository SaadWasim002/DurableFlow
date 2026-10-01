package com.durableflow.execution;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.durableflow.execution.TaskHandlers.TaskFailure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskHandlersTests {
    private final TaskHandlers handlers = new TaskHandlers();

    @Test
    void echoAndSleepReturnTheirInput() throws Exception {
        var input = Map.<String, Object>of("message", "hello", "millis", 0);
        assertThat(handlers.execute("echo", input)).isEqualTo(input);
        assertThat(handlers.execute("sleep", input)).isEqualTo(input);
    }

    @Test
    void failurePreservesItsReasonAndType() {
        assertThatThrownBy(() -> handlers.execute("fail", Map.of("message", "provider unavailable")))
                .isInstanceOfSatisfying(TaskFailure.class, error -> {
                    assertThat(error.errorType()).isEqualTo("DEMO_FAILURE");
                    assertThat(error.getMessage()).isEqualTo("provider unavailable");
                });
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 60001, Long.MAX_VALUE})
    void rejectsSleepOutsideItsRange(long millis) {
        assertThatThrownBy(() -> handlers.execute("sleep", Map.of("millis", millis)))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("integer from 0 to 60000");
    }

    @Test
    void rejectsFractionalOrStringSleepAndNonStringFailureMessages() {
        for (Object millis : new Object[] {1.5, "10"}) {
            assertThatThrownBy(() -> handlers.execute("sleep", Map.of("millis", millis)))
                    .isInstanceOf(TaskFailure.class);
        }
        assertThatThrownBy(() -> handlers.execute("fail", Map.of("message", 7)))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("string");
    }
}
