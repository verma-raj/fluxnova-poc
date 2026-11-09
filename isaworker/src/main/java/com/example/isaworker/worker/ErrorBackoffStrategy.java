package com.example.isaworker.worker;

import org.finos.fluxnova.bpm.client.backoff.ErrorAwareBackoffStrategy;
import org.finos.fluxnova.bpm.client.exception.ExternalTaskClientException;
import org.finos.fluxnova.bpm.client.task.ExternalTask;

import java.time.Duration;
import java.util.List;

/**
 * Back-off that suits long polling.
 * <ul>
 *   <li>A poll that <em>failed</em> (engine down, wrong credentials, network) is followed by a pause that grows
 *       exponentially with every consecutive failure, up to {@code max}. A tenant whose engine is unreachable therefore
 *       costs almost nothing, and the first poll after the engine is back succeeds within {@code max}.</li>
 *   <li>A poll that returned no task is followed by only a tiny pause: with long polling the engine has already held the
 *       request for the whole {@code asyncResponseTimeout}, so waiting longer would only delay the next task. (The
 *       client's default strategy would stretch this pause to a minute.) The small floor protects the engine in case it
 *       ever answers an empty poll at once instead of holding it.</li>
 *   <li>A poll that returned tasks is followed by no pause.</li>
 * </ul>
 * The client calls this from its single polling thread; the fields are volatile because the state is also read in tests.
 */
public final class ErrorBackoffStrategy implements ErrorAwareBackoffStrategy {

    static final long IDLE_FLOOR_MILLIS = 100;

    private final long initialMillis;
    private final long maxMillis;
    private final double factor;

    private volatile int consecutiveFailures;
    private volatile boolean lastPollWasEmpty;

    public ErrorBackoffStrategy(Duration initial, Duration max, double factor) {
        this.initialMillis = Math.max(1, initial.toMillis());
        this.maxMillis = Math.max(this.initialMillis, max.toMillis());
        this.factor = Math.max(1.0, factor);
    }

    @Override
    public void reconfigure(List<ExternalTask> externalTasks, ExternalTaskClientException exception) {
        if (exception != null) {
            consecutiveFailures = Math.min(consecutiveFailures + 1, 62);
            lastPollWasEmpty = false;
        } else {
            consecutiveFailures = 0;
            lastPollWasEmpty = externalTasks == null || externalTasks.isEmpty();
        }
    }

    @Override
    public long calculateBackoffTime() {
        int failures = consecutiveFailures;
        if (failures > 0) {
            double pause = initialMillis * Math.pow(factor, failures - 1);
            return (long) Math.min(pause, maxMillis);
        }
        return lastPollWasEmpty ? IDLE_FLOOR_MILLIS : 0;
    }
}
