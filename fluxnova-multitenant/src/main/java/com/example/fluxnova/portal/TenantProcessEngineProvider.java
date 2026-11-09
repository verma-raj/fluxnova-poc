package com.example.fluxnova.portal;

import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.finos.fluxnova.bpm.engine.rest.spi.ProcessEngineProvider;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

/**
 * Tells the Fluxnova web portal (Welcome / Cockpit / Tasklist / Admin) and the REST API which engines exist.
 * <p>
 * Every tenant is one engine, addressed by its tenant id: {@code /app/cockpit/tenant-a/},
 * {@code /engine-rest/engine/tenant-a/process-definition}, and so on. The list is live, so a tenant onboarded at runtime
 * appears in the portal's engine selector without a restart.
 * <p>
 * Registered through {@code META-INF/services}. It is found before the starter's single-engine
 * {@code SpringBootProcessEngineProvider} because application classes come first on the class path.
 */
public class TenantProcessEngineProvider implements ProcessEngineProvider {

    /** The engine of the alphabetically first tenant, used where no engine is named. Null while no tenant runs. */
    @Override
    public ProcessEngine getDefaultProcessEngine() {
        SortedMap<String, ProcessEngine> engines = TenantEngineDirectory.engines();
        return engines.isEmpty() ? null : engines.get(engines.firstKey());
    }

    @Override
    public ProcessEngine getProcessEngine(String name) {
        return name == null ? null : TenantEngineDirectory.engines().get(name);
    }

    @Override
    public Set<String> getProcessEngineNames() {
        return Collections.unmodifiableSet(TenantEngineDirectory.engines().keySet());
    }

    /** All running engines by name (added to the interface in Fluxnova 3.0). A read-only snapshot, ordered by name. */
    @Override
    public Map<String, ProcessEngine> getProcessEngines() {
        return Collections.unmodifiableMap(TenantEngineDirectory.engines());
    }
}
