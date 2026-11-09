package com.example.isaworker.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Watches {@code fluxnova-engine.yml} and restarts the application when the engine endpoints (or any other setting the
 * file carries) changed, so that it reads them afresh. It does not change anything in the running workers itself: a
 * restart is the only way a change is applied, and the container's restart policy brings the application back (see
 * {@link ApplicationExitRestart}).
 * <p>
 * It compares what the file says <em>now</em> with the settings the workers were started with
 * ({@link EngineConfigReader#startedWith()}), not with an earlier version of the file. So:
 * <ul>
 *   <li>an edit that leaves the settings as they are (a comment, the order of the keys, spaces) restarts nothing: a restart
 *       interrupts the dialogs that are open, so it is not done for nothing;</li>
 *   <li>after the restart the new application has started with exactly what the file says, so it finds nothing to change
 *       and does not restart again.</li>
 * </ul>
 * <b>Why polling and not a {@code WatchService}:</b> the file normally lives in a folder of the Windows host that is
 * bind-mounted into a Linux container. Such mounts do not deliver inotify events, so a WatchService would silently miss
 * edits. Reading and hashing a few hundred bytes every {@code fluxnova-workers.config.poll-interval} works everywhere,
 * including when an editor replaces the file instead of changing it.
 * <p>
 * Safety rules, because a half-saved or mistyped file must never take working workers down:
 * <ul>
 *   <li><b>Settling.</b> A changed file is acted on only when two consecutive looks read the same content, so a file that
 *       is still being written never causes a restart.</li>
 *   <li><b>Invalid file.</b> A file that cannot be parsed is reported once (log, and {@code /actuator/health}) and ignored;
 *       the running workers are not touched. Fixing the file restarts the application.</li>
 *   <li><b>Missing file.</b> A file that disappears (or cannot be read for a moment) changes nothing. To stop serving
 *       every tenant, leave {@code tenants: []} in the file.</li>
 * </ul>
 * Switched off with {@code fluxnova-workers.config.restart-on-change=false}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fluxnova-workers.config", name = "restart-on-change", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class EngineConfigWatcher {

    private final EngineConfigFileProperties properties;
    private final EngineConfigLoader loader;
    private final EngineConfigReader reader;
    private final RestartAction restart;

    // check() is synchronized and the scheduler never overlaps runs of one task: one thread touches these at a time.
    /** A changed version seen once; it is acted on when the next look sees it unchanged. */
    private String candidateSha;
    /** The newest version that needed no restart: same settings as the running ones, or invalid. Not parsed again. */
    private String evaluatedSha;
    private String lastReadProblem;
    private String lastError;
    private boolean restartRequested;

    private volatile WatchStatus status = new WatchStatus(null, null, false);

    /** Runs {@code poll-interval} after start-up and then {@code poll-interval} after each run has finished. */
    @Scheduled(initialDelayString = "${fluxnova-workers.config.poll-interval:PT5S}",
            fixedDelayString = "${fluxnova-workers.config.poll-interval:PT5S}")
    public synchronized void check() {
        try {
            poll();
        } catch (RuntimeException | LinkageError e) {
            // Never let one bad run stop the watcher; the next run starts from a clean slate.
            log.error("Unexpected error while checking {}; will try again in the next run", properties.getFile(), e);
        }
    }

    /** Read-only view for the health endpoint. */
    public WatchStatus status() {
        return status;
    }

    private void poll() {
        TenantWorkerProperties running = reader.startedWith();
        if (running == null || restartRequested) {
            return;                                           // the workers are not started yet / the restart is under way
        }
        Path file = Path.of(properties.getFile());
        Instant now = Instant.now();
        byte[] content = read(file);
        if (content == null) {
            publish(now);
            return;
        }

        String sha = sha256(content);
        if (sha.equals(evaluatedSha)) {
            candidateSha = null;                              // nothing new (or the newest version was undone again)
            publish(now);
            return;
        }
        if (!sha.equals(candidateSha)) {
            candidateSha = sha;                               // first sight of a change: wait until it stops changing
            log.debug("{} changed; waiting for the edit to settle", file);
            publish(now);
            return;
        }
        candidateSha = null;

        try {
            TenantWorkerProperties wanted = loader.parse(content);
            lastError = null;                                 // the newest version is valid: an earlier "invalid" no longer applies
            if (wanted.equals(running)) {
                evaluatedSha = sha;
                log.info("{} changed, but the engine endpoints and settings are the same: nothing to restart", file);
            } else {
                restartRequested = true;
                restart.restart(describe(file, wanted, running));
            }
        } catch (EngineConfigException e) {
            evaluatedSha = sha;
            lastError = e.getMessage();
            log.error("Ignoring invalid {}: {} The workers keep running with the endpoints they started with; fix the file and "
                    + "the application restarts.", file, e.getMessage());
        }
        publish(now);
    }

    private static String describe(Path file, TenantWorkerProperties wanted, TenantWorkerProperties running) {
        return file + " changed: tenants were " + ids(running) + ", now " + ids(wanted)
                + ". Restarting so that the new endpoints are read.";
    }

    private static List<String> ids(TenantWorkerProperties settings) {
        return settings.getTenants() == null ? List.of()
                : settings.getTenants().stream().map(t -> t == null ? "<empty entry>" : String.valueOf(t.getId())).toList();
    }

    private void publish(Instant now) {
        status = new WatchStatus(now, lastError, restartRequested);
    }

    /** @return the file content, or {@code null} if it is missing / unreadable right now (reported once per state). */
    private byte[] read(Path file) {
        try {
            byte[] content = Files.readAllBytes(file);
            if (lastReadProblem != null) {
                log.info("{} is readable again", file);
                lastReadProblem = null;
            }
            return content;
        } catch (NoSuchFileException e) {
            reportReadProblem(file, "does not exist; the workers keep running with the endpoints they started with");
        } catch (IOException e) {
            reportReadProblem(file, "cannot be read: " + e + "; the workers keep running with the endpoints they started with");
        }
        return null;
    }

    private void reportReadProblem(Path file, String problem) {
        if (!problem.equals(lastReadProblem)) {
            lastReadProblem = problem;
            log.warn("{} {}", file, problem);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to exist on every JVM", e);
        }
    }

    /**
     * What the watcher currently knows.
     *
     * @param lastCheckedAt    when the file was last looked at ({@code null} before the first look)
     * @param lastError        why the newest version of the file was ignored, or {@code null}
     * @param restartRequested {@code true} once a restart has been asked for
     */
    public record WatchStatus(Instant lastCheckedAt, String lastError, boolean restartRequested) {
    }
}
