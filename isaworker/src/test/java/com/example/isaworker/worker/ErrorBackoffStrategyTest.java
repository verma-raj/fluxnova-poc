package com.example.isaworker.worker;

import org.finos.fluxnova.bpm.client.exception.ExternalTaskClientException;
import org.finos.fluxnova.bpm.client.task.ExternalTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ErrorBackoffStrategyTest {

    private final ErrorBackoffStrategy strategy =
            new ErrorBackoffStrategy(Duration.ofMillis(500), Duration.ofSeconds(4), 2.0);
    private final ExternalTaskClientException failure = new ExternalTaskClientException("engine down");

    @Test
    void aFailedPollBacksOffExponentiallyUpToTheMaximum() {
        assertThat(strategy.calculateBackoffTime()).isZero();           // nothing has happened yet
        long[] expected = {500, 1_000, 2_000, 4_000, 4_000, 4_000};
        for (long pause : expected) {
            strategy.reconfigure(List.of(), failure);
            assertThat(strategy.calculateBackoffTime()).isEqualTo(pause);
        }
    }

    @Test
    void aSuccessfulPollEndsTheBackOffAtOnce() {
        strategy.reconfigure(List.of(), failure);
        strategy.reconfigure(List.of(), failure);
        assertThat(strategy.calculateBackoffTime()).isEqualTo(1_000);

        strategy.reconfigure(List.of(mock(ExternalTask.class)), null);
        assertThat(strategy.calculateBackoffTime()).isZero();           // tasks were returned: poll again immediately
    }

    @Test
    void anEmptyLongPollIsFollowedByOnlyATinyPause() {
        strategy.reconfigure(List.of(), null);
        assertThat(strategy.calculateBackoffTime()).isEqualTo(ErrorBackoffStrategy.IDLE_FLOOR_MILLIS);

        strategy.reconfigure(null, null);                               // a null list counts as empty
        assertThat(strategy.calculateBackoffTime()).isEqualTo(ErrorBackoffStrategy.IDLE_FLOOR_MILLIS);
    }

    @Test
    void manyFailuresInARowNeverOverflow() {
        for (int i = 0; i < 500; i++) {
            strategy.reconfigure(List.of(), failure);
        }
        assertThat(strategy.calculateBackoffTime()).isEqualTo(4_000);
    }
}
