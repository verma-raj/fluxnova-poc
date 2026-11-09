package com.example.fluxnova.deploy;

import com.example.fluxnova.config.RuntimeProperties;
import com.example.fluxnova.engine.FluxnovaEngineManager;
import com.example.fluxnova.engine.TenantEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.finos.fluxnova.bpm.engine.RepositoryService;
import org.finos.fluxnova.bpm.engine.repository.Deployment;
import org.finos.fluxnova.bpm.engine.repository.ProcessDefinition;
import org.finos.fluxnova.bpm.engine.runtime.ProcessInstance;
import org.finos.fluxnova.bpm.model.bpmn.BpmnModelInstance;
import org.finos.fluxnova.bpm.model.bpmn.instance.Process;
import org.finos.fluxnova.bpm.model.bpmn.instance.StartEvent;
import org.finos.fluxnova.bpm.model.xml.instance.ModelElementInstance;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Gives every tenant its own folder for {@code .bpmn} files, deploys what lands in it to that tenant's engine, and starts
 * the process.
 * <p>
 * <b>One folder per tenant.</b> As soon as a tenant is running, the folder {@code <folder>/<tenant id>} is created (on the
 * host it appears next to the other tenants' folders, because the parent folder is the volume). A file in a tenant's
 * folder is deployed to <em>that tenant's engine only</em>; no other tenant ever sees it. A {@code .bpmn} file directly in
 * the parent folder belongs to no tenant: it is ignored, with a warning.
 * <p>
 * <b>What happens to a file.</b> Once the file is complete it is deployed to the tenant's engine (a new version of the
 * process definition if the content changed) and one instance is started of every process in it that has a plain
 * (none) start event. A process that starts by itself - timer, message or signal start event - is only deployed: starting
 * it by hand as well would run it twice.
 * <p>
 * <b>Onboarding and restarts start nothing.</b> A file that is already in a tenant's folder when the watcher first meets
 * that tenant's engine - the application was (re)started, the tenant was just onboarded, or it was put back into
 * {@code tenants.yml} - is deployed (so that the process can be started by REST or from the portal) but no instance is
 * started. Only a file that <em>lands</em> afterwards, or is <em>changed</em> afterwards, starts a process.
 * <p>
 * <b>Nothing is run twice.</b> A file that was already deployed with identical content is not deployed again: the engine's
 * duplicate filter recognises it. Changing the file deploys a new version and starts a new instance; removing the file and
 * putting it back, identical, starts a new instance as well, because the file has landed again.
 * <p>
 * <b>Why polling and not {@code WatchService}:</b> the folder is normally a bind mount of a Windows or network folder
 * into a Linux container, and such mounts do not deliver inotify events (see {@code TenantConfigWatcher}).
 * <p>
 * <b>Half-written files.</b> A file is only used once its size and modification time are the same in two consecutive scans,
 * so a copy that is still in progress is never deployed.
 * <p>
 * <b>Failures.</b> A file that cannot be deployed (not valid BPMN, no {@code historyTimeToLive}, database trouble) is
 * reported and retried a few times with back-off (the cause may be temporary), then left alone until the file changes; a
 * changed file is tried again at once. A failure in one file or one tenant never affects the others, and the folder
 * being missing never stops the application.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BpmnFolderWatcher {

    static final String SOURCE = "bpmn-deployment-folder";
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_EVENTS = 50;
    private static final Duration FIRST_RETRY = Duration.ofSeconds(15);
    /** A file that cannot be deployed is tried this often (15 s, 30 s, 1 min, 2 min between tries), then left alone until it changes. */
    private static final int MAX_ATTEMPTS = 5;
    /** A file that was gone for this long counts as removed; a shorter gap (an editor's save, a mount hiccup) does not. */
    private static final Duration VANISH_GRACE = Duration.ofSeconds(10);

    private final RuntimeProperties runtime;
    private final FluxnovaEngineManager engineManager;

    // scan() is synchronized and the scheduler never overlaps runs of one task, so these are touched by one thread only.
    private final Map<Path, Fingerprint> lastSeen = new HashMap<>();
    private final Map<Key, Handled> handled = new HashMap<>();
    private final Map<String, Instant> absentSince = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    private final Deque<FileEvent> events = new ArrayDeque<>();
    /**
     * Files ("tenant-a/order.bpmn") that were already in a tenant's folder when the watcher first met that tenant's engine
     * (application start, tenant onboarded, tenant put back): they have not "just landed", so they are deployed but nothing
     * is started for them. A file leaves this set when it has been deployed once, or when it was gone for a while.
     */
    private final Set<String> notToStart = new HashSet<>();
    /** The engine of every tenant as the watcher last saw it: a different engine means the tenant was (re)onboarded. */
    private final Map<String, WeakReference<ProcessEngine>> seenEngines = new HashMap<>();
    private String lastProblem;

    private volatile FolderStatus status;
    private volatile List<TenantFolder> tenantFolders = List.of();

    @Scheduled(fixedDelayString = "${fluxnova.runtime.bpmn-deployment.poll-interval:PT3S}")
    public synchronized void scan() {
        String directory = runtime.getBpmnDeployment().getDirectory();
        if (directory == null || directory.isBlank() || engineManager.isShuttingDown()) {
            return;
        }
        try {
            scan(Path.of(directory.trim()));
        } catch (RuntimeException | LinkageError e) {
            // Never let one bad run stop the watcher; the next run starts from a clean slate.
            log.error("Unexpected error while scanning the BPMN deployment folder {}; will try again", directory, e);
        }
    }

    /** Read-only view for the management endpoint. */
    public FolderStatus status() {
        FolderStatus current = status;
        if (current != null) {
            return current;
        }
        String directory = runtime.getBpmnDeployment().getDirectory();
        boolean enabled = directory != null && !directory.isBlank();
        return new FolderStatus(enabled, enabled ? directory.trim() : null, false, null, null, List.of(), List.of());
    }

    private void scan(Path root) {
        Instant now = Instant.now();
        if (!Files.isDirectory(root)) {
            reportProblem(root, "does not exist or is not a folder (is it mounted into the container?)");
            publish(root, false, now);
            return;
        }
        ensureTenantFolders(root);
        Listing listing;
        try {
            listing = list(root);
        } catch (IOException e) {
            reportProblem(root, "cannot be read: " + e);
            publish(root, true, now);
            return;
        }
        reportProblem(root, null);
        List<Candidate> candidates = listing.candidates();
        warnAboutFilesInTheRoot(root, listing.rootFiles());

        Set<String> names = new HashSet<>();
        Set<Path> paths = new HashSet<>();
        candidates.forEach(c -> {
            names.add(c.name());
            paths.add(c.file());
        });
        markFilesOfNewEngines(candidates);
        lastSeen.keySet().retainAll(paths);
        forgetVanishedFiles(names, now);

        for (Candidate candidate : candidates) {
            if (engineManager.isShuttingDown()) {
                return;
            }
            Fingerprint fingerprint = fingerprintOf(candidate.file());
            if (fingerprint == null || fingerprint.size() == 0) {
                continue;
            }
            // First sight, or still being written: wait until two consecutive scans see the same size and time.
            if (!fingerprint.equals(lastSeen.put(candidate.file(), fingerprint))) {
                continue;
            }
            try {
                handle(candidate, fingerprint, now);
            } catch (RuntimeException e) {
                log.error("Unexpected error while handling {}", candidate.name(), e);
            }
        }
        publish(root, true, now);
    }

    /**
     * Creates {@code <root>/<tenant id>} for every running tenant that has no folder yet. The parent folder is the volume,
     * so the new folder shows up on the host at once. Folders of offboarded tenants are left alone: the files in them are
     * the owner's, and putting the tenant back reuses them.
     */
    private void ensureTenantFolders(Path root) {
        List<TenantFolder> folders = new ArrayList<>();
        for (TenantEngine tenant : engineManager.activeTenants()) {
            String id = tenant.getTenantId();
            Path folder = root.resolve(id);
            folders.add(new TenantFolder(id, folder.toString()));
            if (Files.isDirectory(folder)) {
                warned.remove("mkdir|" + id);
                continue;
            }
            try {
                Files.createDirectories(folder);
                warned.remove("mkdir|" + id);
                log.info("Created the BPMN folder of tenant '{}': {} - .bpmn files put there are deployed to this tenant only", id, folder);
            } catch (IOException | RuntimeException e) {
                if (warned.add("mkdir|" + id)) {
                    log.warn("Cannot create the BPMN folder {} of tenant '{}': {} (is the volume mounted read-only, or not writable "
                            + "for the application's user?). The folder can be created by hand.", folder, id, e.toString());
                }
            }
        }
        tenantFolders = List.copyOf(folders);
    }

    /**
     * A tenant engine the watcher has not seen before (application start, tenant onboarded, tenant put back): what is in the
     * tenant's folder at that moment is deployed but not started. Only what lands or changes afterwards starts a process.
     */
    private void markFilesOfNewEngines(List<Candidate> candidates) {
        Set<String> active = new HashSet<>();
        for (TenantEngine tenant : engineManager.activeTenants()) {
            String id = tenant.getTenantId();
            active.add(id);
            WeakReference<ProcessEngine> seen = seenEngines.get(id);
            if (seen != null && seen.get() == tenant.getEngine()) {
                continue;
            }
            seenEngines.put(id, new WeakReference<>(tenant.getEngine()));
            for (Candidate candidate : candidates) {
                if (id.equals(candidate.tenantScope())) {
                    notToStart.add(candidate.name());
                }
            }
        }
        seenEngines.keySet().retainAll(active);
    }

    /** A {@code .bpmn} file directly in the root belongs to no tenant: say so once instead of silently doing nothing. */
    private void warnAboutFilesInTheRoot(Path root, List<String> rootFiles) {
        Set<String> current = new HashSet<>();
        for (String name : rootFiles) {
            String key = "root|" + name;
            current.add(key);
            if (warned.add(key)) {
                log.warn("{} is directly in the BPMN deployment folder {} and is ignored: every tenant has its own sub-folder "
                        + "({}/<tenant id>/). Move the file into the sub-folder of the tenant that is to run it.", name, root, root);
            }
        }
        warned.removeIf(w -> w.startsWith("root|") && !current.contains(w));
    }

    /** The {@code .bpmn} files in the tenants' sub-folders (sorted), and the ones that sit directly in the root. */
    private Listing list(Path root) throws IOException {
        List<Candidate> found = new ArrayList<>();
        List<String> rootFiles = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (ignored(name)) {
                    continue;
                }
                if (Files.isDirectory(entry)) {
                    try (Stream<Path> inner = Files.list(entry)) {
                        for (Path file : inner.sorted().toList()) {
                            if (isBpmnFile(file)) {
                                found.add(new Candidate(file, name, name + "/" + file.getFileName()));
                            }
                        }
                    } catch (IOException e) {
                        log.debug("Cannot list {}: {}", entry, e.toString());
                    }
                } else if (isBpmnFile(entry)) {
                    rootFiles.add(name);
                }
            }
        }
        return new Listing(found, rootFiles);
    }

    private static boolean isBpmnFile(Path file) {
        String name = file.getFileName().toString();
        return !ignored(name) && name.toLowerCase(Locale.ROOT).endsWith(".bpmn") && Files.isRegularFile(file);
    }

    private static boolean ignored(String name) {
        return name.startsWith(".") || name.startsWith("~");     // hidden files, editor / Office lock and temp files
    }

    private static Fingerprint fingerprintOf(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return new Fingerprint(attributes.size(), attributes.lastModifiedTime().toMillis());
        } catch (IOException e) {
            return null;                                         // gone or unreadable right now: look again next time
        }
    }

    /** Forgets what is known about a file that has been gone for a while, so putting it back counts as a new landing. */
    private void forgetVanishedFiles(Set<String> names, Instant now) {
        Set<String> known = new HashSet<>(notToStart);
        handled.keySet().forEach(k -> known.add(k.file()));
        for (String name : known) {
            if (names.contains(name)) {
                absentSince.remove(name);
                continue;
            }
            Instant since = absentSince.computeIfAbsent(name, n -> now);
            if (!now.isBefore(since.plus(VANISH_GRACE))) {
                handled.keySet().removeIf(k -> k.file().equals(name));
                notToStart.remove(name);
                absentSince.remove(name);
                warned.removeIf(w -> w.startsWith(name + "|"));
                log.info("{} was removed from the BPMN deployment folder. The deployed process stays in the engine(s).", name);
            }
        }
    }

    /** Deploys (and, if the file has landed, starts) one file of one tenant's folder - on that tenant's engine only. */
    private void handle(Candidate candidate, Fingerprint fingerprint, Instant now) {
        Optional<TenantEngine> running = engineManager.find(candidate.tenantScope());
        if (running.isEmpty()) {
            if (warned.add(candidate.name() + "|no-tenant")) {
                log.warn("{} is in the folder of tenant '{}', but no engine runs for that tenant. It is deployed, without starting "
                        + "anything, when the tenant is running.", candidate.name(), candidate.tenantScope());
            }
            return;
        }
        warned.remove(candidate.name() + "|no-tenant");

        TenantEngine tenant = running.get();
        Key key = new Key(tenant.getTenantId(), candidate.name());
        Handled before = handled.get(key);
        boolean sameEngine = before != null && before.engine().get() == tenant.getEngine();
        boolean unchanged = sameEngine && fingerprint.equals(before.fingerprint());

        if (sameEngine && before.retryAt() == null && unchanged) {
            return;                                              // this exact file is already done for this tenant
        }
        if (sameEngine && before.retryAt() != null && unchanged && now.isBefore(before.retryAt())) {
            return;                                              // failed earlier, waiting for the back-off to end
        }
        byte[] content;
        String sha;
        try {
            if (fingerprint.size() > MAX_FILE_BYTES) {
                throw new IOException("file is larger than " + MAX_FILE_BYTES / 1024 / 1024 + " MB");
            }
            content = Files.readAllBytes(candidate.file());
            sha = sha256(content);
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", candidate.file(), e.toString());
            return;
        }
        if (sameEngine && before.retryAt() == null && sha.equals(before.sha())) {
            // Only the modification time changed (touched / saved without changes): nothing to do.
            handled.put(key, new Handled(sha, fingerprint, before.engine(), null, 0, null));
            return;
        }

        // A file that was already in the tenant's folder when its engine came up is deployed, never started.
        boolean landedLive = !notToStart.contains(candidate.name());
        Instant at = Instant.now();
        try {
            Result result = deployAndStart(tenant, candidate, content, landedLive);
            handled.put(key, new Handled(sha, fingerprint, new WeakReference<>(tenant.getEngine()), null, 0, null));
            notToStart.remove(candidate.name());                 // deployed once: a change of the file now starts a new instance
            record(at, tenant.getTenantId(), candidate.name(), result.outcome(), result.detail());
            if (result.outcome() == Outcome.START_FAILED) {
                log.warn("Tenant '{}': {} - {}", tenant.getTenantId(), candidate.name(), result.detail());
            } else {
                log.info("Tenant '{}': {} - {} - {}", tenant.getTenantId(), candidate.name(), result.outcome(), result.detail());
            }
        } catch (RuntimeException e) {
            int attempts = sameEngine && before.retryAt() != null && unchanged ? before.attempts() + 1 : 1;
            boolean giveUp = attempts >= MAX_ATTEMPTS;
            Duration delay = FIRST_RETRY.multipliedBy(1L << Math.min(attempts - 1, 10));
            String error = firstLine(e);
            // Giving up = retry only when the file (or the tenant's engine) changes. Without a limit, a model that can
            // never be deployed would make the engine log an error for it every few minutes, for ever.
            handled.put(key, new Handled(sha, fingerprint, new WeakReference<>(tenant.getEngine()),
                    giveUp ? Instant.MAX : now.plus(delay), attempts, error));
            // Say it when it is new (first failure, a changed file, another error); a quiet back-off retry stays quiet.
            if (before == null || !sameEngine || !unchanged || !error.equals(before.error()) || giveUp) {
                record(at, tenant.getTenantId(), candidate.name(), Outcome.FAILED,
                        giveUp ? error + " (gave up after " + attempts + " attempts; save the file again to retry)" : error);
                if (giveUp) {
                    log.warn("Tenant '{}': {} could not be deployed after {} attempts, giving up until the file changes: {}",
                            tenant.getTenantId(), candidate.name(), attempts, error);
                } else {
                    log.warn("Tenant '{}': {} could not be deployed (attempt {}, next try in {} s unless the file changes): {}",
                            tenant.getTenantId(), candidate.name(), attempts, delay.toSeconds(), error);
                }
            }
        }
    }

    /**
     * Deploys the file to one tenant's engine and starts what is to be started. Throws if the deployment itself fails
     * (the file is then retried); a failing <em>start</em> is reported in the result instead, because the deployment is
     * already committed and starting again could create a second instance.
     */
    private Result deployAndStart(TenantEngine tenant, Candidate candidate, byte[] content, boolean landedLive) {
        ProcessEngine engine = tenant.getEngine();
        RepositoryService repository = engine.getRepositoryService();
        String resourceName = candidate.file().getFileName().toString();

        Deployment previous = repository.createDeploymentQuery().deploymentName(candidate.name())
                .orderByDeploymentTime().desc().listPage(0, 1).stream().findFirst().orElse(null);
        Deployment deployment = repository.createDeployment()
                .name(candidate.name())
                .source(SOURCE)
                .enableDuplicateFiltering(false)       // identical content to the latest deployment of this name: not deployed again
                .addInputStream(resourceName, new ByteArrayInputStream(content))
                .deploy();
        boolean isNewDeployment = previous == null || !previous.getId().equals(deployment.getId());

        List<ProcessDefinition> definitions = repository.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).orderByProcessDefinitionKey().asc().list();
        if (definitions.isEmpty()) {
            return new Result(isNewDeployment ? Outcome.DEPLOYED : Outcome.ALREADY_DEPLOYED,
                    "deployment " + deployment.getId() + " contains no executable process (isExecutable=\"true\" is missing?)");
        }

        // Only a file that landed (or changed) after the tenant's engine came up is run. Not on onboarding, not on a restart.
        boolean autoStart = runtime.getBpmnDeployment().isAutoStart();
        boolean start = autoStart && landedLive;
        List<String> notes = new ArrayList<>();
        int started = 0;
        boolean startFailed = false;
        for (ProcessDefinition definition : definitions) {
            String label = "process '" + definition.getKey() + "' v" + definition.getVersion();
            if (!start) {
                String why = !autoStart ? "auto-start is off"
                        : "the file was already in the folder when this tenant's engine started; changing the file starts it";
                notes.add(label + (isNewDeployment ? " deployed" : " already deployed with identical content") + ", nothing started (" + why + ")");
                continue;
            }
            Boolean none = hasNoneStartEvent(repository, definition);
            if (none == null) {
                notes.add(label + " deployed, but its start events could not be read, so no instance was started");
            } else if (!none) {
                notes.add(label + " deployed, not started here: it starts by itself (timer / message / signal start event)");
            } else {
                try {
                    ProcessInstance instance = engine.getRuntimeService().startProcessInstanceById(definition.getId());
                    started++;
                    notes.add(label + " -> started instance " + instance.getId() + (instance.isEnded() ? " (already finished)" : " (running)"));
                } catch (RuntimeException e) {
                    startFailed = true;
                    notes.add(label + " deployed, but starting it failed: " + firstLine(e));
                }
            }
        }
        String detail = "deployment " + deployment.getId() + "; " + String.join("; ", notes);
        Outcome outcome = startFailed ? Outcome.START_FAILED
                : started > 0 ? Outcome.DEPLOYED_AND_STARTED
                : isNewDeployment ? Outcome.DEPLOYED : Outcome.ALREADY_DEPLOYED;
        return new Result(outcome, detail);
    }

    /**
     * @return true if the process has a plain start event (it is started by a call), false if all its start events are
     *         timer / message / signal ... events (the engine starts it), null if the model could not be read
     */
    private static Boolean hasNoneStartEvent(RepositoryService repository, ProcessDefinition definition) {
        try {
            BpmnModelInstance model = repository.getBpmnModelInstance(definition.getId());
            ModelElementInstance element = model.getModelElementById(definition.getKey());
            if (!(element instanceof Process process)) {
                return null;
            }
            return process.getChildElementsByType(StartEvent.class).stream().anyMatch(s -> s.getEventDefinitions().isEmpty());
        } catch (RuntimeException e) {
            log.debug("Cannot read the start events of {}: {}", definition.getKey(), e.toString());
            return null;
        }
    }

    private void record(Instant at, String tenantId, String file, Outcome outcome, String detail) {
        events.addFirst(new FileEvent(at, tenantId, file, outcome.name(), detail));
        while (events.size() > MAX_EVENTS) {
            events.removeLast();
        }
    }

    private void reportProblem(Path root, String problem) {
        if (problem == null) {
            if (lastProblem != null) {
                log.info("BPMN deployment folder {} is available", root);
                lastProblem = null;
            }
        } else if (!problem.equals(lastProblem)) {
            lastProblem = problem;
            log.warn("BPMN deployment folder {} {}", root, problem);
        }
    }

    private void publish(Path root, boolean folderFound, Instant at) {
        status = new FolderStatus(true, root.toString(), folderFound, at, lastProblem, tenantFolders, List.copyOf(events));
    }

    private static String firstLine(Throwable e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        int end = message.indexOf('\n');
        message = (end < 0 ? message : message.substring(0, end)).trim();
        return message.length() > 400 ? message.substring(0, 400) + "..." : message;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to exist on every JVM", e);
        }
    }

    private enum Outcome {
        /** Deployed, and an instance of every process with a plain start event was started. */
        DEPLOYED_AND_STARTED,
        /** Deployed; nothing started (timer / message start event, auto-start off, or the file was there before the tenant started). */
        DEPLOYED,
        /** The engine already had this exact content, so nothing was deployed or started. */
        ALREADY_DEPLOYED,
        /** Deployed, but starting an instance failed (for example a service task needs variables). */
        START_FAILED,
        /** Not deployed (invalid model, database error, ...); retried with back-off. */
        FAILED
    }

    private record Result(Outcome outcome, String detail) {
    }

    /** A file in a tenant's folder; {@code tenantScope} is the tenant, {@code name} is "tenant/file.bpmn". */
    private record Candidate(Path file, String tenantScope, String name) {
    }

    /** What is in the watched folder: the tenants' files, and the files that sit directly in the root (which belong to nobody). */
    private record Listing(List<Candidate> candidates, List<String> rootFiles) {
    }

    private record Fingerprint(long size, long modifiedMillis) {
    }

    private record Key(String tenantId, String file) {
    }

    /** What was last done with one file for one tenant (and which engine instance it was done on). */
    private record Handled(String sha, Fingerprint fingerprint, WeakReference<ProcessEngine> engine, Instant retryAt,
                           int attempts, String error) {
    }

    /** One thing that happened to a file, newest first in {@link FolderStatus#recent()}. */
    public record FileEvent(Instant at, String tenantId, String file, String outcome, String detail) {
    }

    /** The folder a running tenant's .bpmn files go into. */
    public record TenantFolder(String tenant, String folder) {
    }

    /** What the watcher currently knows about the folder. */
    public record FolderStatus(boolean enabled, String directory, boolean folderFound, Instant lastScanAt, String problem,
                               List<TenantFolder> tenantFolders, List<FileEvent> recent) {
    }
}
