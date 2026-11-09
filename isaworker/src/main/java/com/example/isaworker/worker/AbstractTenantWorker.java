package com.example.isaworker.worker;

import com.example.isaworker.gui.UserCancelledException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.client.task.ExternalTask;
import org.finos.fluxnova.bpm.client.task.ExternalTaskHandler;
import org.finos.fluxnova.bpm.client.task.ExternalTaskService;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

/**
 * Shared behaviour of the task handlers: every handler instance belongs to <em>one</em> tenant, runs its business logic,
 * completes the task with the returned variables and turns any exception into a proper failure report.
 * <p>
 * A handler never lets an exception escape into the client's polling thread. If the logic (or the completion call) fails,
 * the task is reported to the engine with {@code handleFailure}: it is retried up to {@link #DEFAULT_RETRIES} times, and
 * when the retries are used up the engine raises an incident that an operator can see in the Monitoring portal. If even
 * that report fails (engine unreachable), the task simply stays locked until its lock expires and is fetched again.
 * <p>
 * A {@link UserCancelledException} (a closed dialog) takes the same route, but is logged as a warning without a stack trace.
 */
@Slf4j
@RequiredArgsConstructor
abstract class AbstractTenantWorker implements ExternalTaskHandler {

    static final int DEFAULT_RETRIES = 3;
    static final long RETRY_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_ERROR_DETAILS = 4_000;

    /** The tenant (as listed in fluxnova-engine.yml) whose engine this handler serves. */
    protected final String tenantId;

    @Override
    public final void execute(ExternalTask task, ExternalTaskService service) {
        try {
            Map<String, Object> result = handle(task);
            service.complete(task, result);
            log.info("[{}] {} task {} completed, returned variables {}", tenantId, task.getTopicName(), task.getId(), result.keySet());
        } catch (UserCancelledException e) {
            log.warn("[{}] {} task {} (process instance {}): {}", tenantId, task.getTopicName(), task.getId(),
                    task.getProcessInstanceId(), e.getMessage());
            reportFailure(task, service, e, e.getMessage());
        } catch (Exception e) {
            log.error("[{}] {} task {} (process instance {}) failed: {}", tenantId, task.getTopicName(), task.getId(),
                    task.getProcessInstanceId(), e.toString(), e);
            reportFailure(task, service, e, stackTrace(e));
        }
    }

    /** The business logic of the topic. Returns the process variables to set when the task is completed. */
    protected abstract Map<String, Object> handle(ExternalTask task) throws Exception;

    /** Pause before the engine offers a failed task again. */
    protected long retryTimeoutMillis() {
        return RETRY_TIMEOUT_MILLIS;
    }

    private void reportFailure(ExternalTask task, ExternalTaskService service, Exception cause, String details) {
        int retriesLeft = task.getRetries() == null ? DEFAULT_RETRIES : task.getRetries() - 1;
        try {
            service.handleFailure(task, summary(cause), cap(details), Math.max(retriesLeft, 0), retryTimeoutMillis());
            log.warn("[{}] {} task {} reported as failed to the engine ({} retr{} left{})", tenantId, task.getTopicName(),
                    task.getId(), Math.max(retriesLeft, 0), retriesLeft == 1 ? "y" : "ies",
                    retriesLeft <= 0 ? ": the engine raises an incident" : "");
        } catch (RuntimeException reportError) {
            log.error("[{}] {} task {}: the failure could not be reported either ({}); the task is fetched again when its "
                    + "lock expires", tenantId, task.getTopicName(), task.getId(), reportError.toString());
        }
    }

    private static String summary(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String stackTrace(Exception e) {
        StringWriter text = new StringWriter();
        e.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static String cap(String details) {
        return details.length() > MAX_ERROR_DETAILS ? details.substring(0, MAX_ERROR_DETAILS) : details;
    }

    /** Logs the engine-level tenant id of the task (only set when the engine itself uses tenant ids). */
    protected final void logStart(ExternalTask task) {
        log.info("[{}] {} task {} received (process instance {}, activity '{}', engine tenant id {}, retries {})", tenantId,
                task.getTopicName(), task.getId(), task.getProcessInstanceId(), task.getActivityId(),
                task.getTenantId(), task.getRetries());
    }
}
