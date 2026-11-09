package com.example.fluxnova.portal;

import org.finos.fluxnova.bpm.engine.ProcessEngine;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Immutable, thread-safe snapshot of the running tenant engines, readable without any Spring context.
 * <p>
 * The web portal and the REST API locate engines through a {@code ServiceLoader}-created SPI object
 * ({@link TenantProcessEngineProvider}), which cannot receive Spring beans. The engine manager therefore publishes a
 * fresh copy here after every change (copy-on-write): readers on request threads always see a consistent map and never
 * touch the global {@code ProcessEngines} registry, whose plain {@code HashMap} is not safe to read while an engine
 * is being registered.
 */
public final class TenantEngineDirectory {

    private static volatile SortedMap<String, ProcessEngine> engines = Collections.emptySortedMap();

    private TenantEngineDirectory() {
    }

    /** Replaces the snapshot. Called by the engine manager, under its lifecycle lock. */
    public static void publish(Map<String, ProcessEngine> running) {
        engines = Collections.unmodifiableSortedMap(new TreeMap<>(running));
    }

    /** Running engines by tenant id / engine name, ordered by name. */
    public static SortedMap<String, ProcessEngine> engines() {
        return engines;
    }
}
