package com.example.isaworker.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineConfigLoaderTest {

    private final TenantWorkerProperties baseline = new TenantWorkerProperties();
    private final MockEnvironment environment = new MockEnvironment().withProperty("TENANT_B_PASSWORD", "from-env");
    private final EngineConfigLoader loader = new EngineConfigLoader(baseline, environment);

    private TenantWorkerProperties parse(String yaml) {
        return loader.parse(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theTenantListIsReadWithBothNamingStyles() {
        TenantWorkerProperties parsed = parse("""
                fluxnova-workers:
                  tenants:
                    - id: "tenant-a"
                      baseUrl: "http://fluxnova:8080/engine-rest/engine/tenant-a"
                    - id: "tenant-b"
                      base-url: "http://fluxnova:8080/engine-rest/engine/tenant-b"
                      username: "worker"
                """);
        assertThat(parsed.getTenants()).extracting(TenantWorkerProperties.Tenant::getId).containsExactly("tenant-a", "tenant-b");
        assertThat(parsed.getTenants()).extracting(TenantWorkerProperties.Tenant::getBaseUrl)
                .containsExactly("http://fluxnova:8080/engine-rest/engine/tenant-a", "http://fluxnova:8080/engine-rest/engine/tenant-b");
        assertThat(parsed.getTenants().get(1).getUsername()).isEqualTo("worker");
    }

    @Test
    void placeholdersAreResolvedFromTheEnvironment() {
        TenantWorkerProperties parsed = parse("""
                fluxnova-workers:
                  tenants:
                    - id: "tenant-b"
                      baseUrl: "http://fluxnova:8080/engine-rest/engine/tenant-b"
                      password: "${TENANT_B_PASSWORD}"
                """);
        assertThat(parsed.getTenants().get(0).getPassword()).isEqualTo("from-env");
    }

    @Test
    void whatTheFileLeavesOutKeepsTheBaselineAndWhatItSetsWins() {
        baseline.setDefaultUsername("env-user");
        baseline.setLockDuration(Duration.ofSeconds(30));
        TenantWorkerProperties parsed = parse("""
                fluxnova-workers:
                  lock-duration: 45s
                  tenants: []
                """);
        assertThat(parsed.getLockDuration()).isEqualTo(Duration.ofSeconds(45));
        assertThat(parsed.getDefaultUsername()).isEqualTo("env-user");
    }

    @Test
    void anEmptyFileOrOneWithoutTenantsMeansNoTenants() {
        assertThat(parse("").getTenants()).isEmpty();
        assertThat(parse("# nothing here\n").getTenants()).isEmpty();
        assertThat(parse("fluxnova-workers:\n").getTenants()).isEmpty();
        assertThat(parse("fluxnova-workers:\n  tenants:\n").getTenants()).isEmpty();
        assertThat(parse("something-else: 1\n").getTenants()).isEmpty();
    }

    @Test
    void parsingNeverChangesTheBaseline() {
        baseline.setTenants(List.of(tenant("packaged")));
        TenantWorkerProperties parsed = parse("""
                fluxnova-workers:
                  tenants:
                    - id: "tenant-a"
                      baseUrl: "http://h:8080/engine-rest/engine/tenant-a"
                """);
        assertThat(parsed.getTenants()).extracting(TenantWorkerProperties.Tenant::getId).containsExactly("tenant-a");
        assertThat(baseline.getTenants()).extracting(TenantWorkerProperties.Tenant::getId).containsExactly("packaged");
        assertThat(loader.baseline().getTenants()).extracting(TenantWorkerProperties.Tenant::getId).containsExactly("packaged");
    }

    @Test
    void aFileWithoutATenantsSectionKeepsThePackagedTenants() {
        baseline.setTenants(List.of(tenant("packaged")));
        assertThat(parse("fluxnova-workers:\n  worker-id-prefix: other\n").getTenants())
                .extracting(TenantWorkerProperties.Tenant::getId).containsExactly("packaged");
    }

    @Test
    void aSyntaxErrorIsReportedWithItsLocationOnOneLine() {
        String badIndent = "fluxnova-workers:\n  tenants:\n   - id: \"tenant-x\"\n      baseUrl: \"http://h/e\"\n";
        assertThatThrownBy(() -> parse(badIndent)).isInstanceOf(EngineConfigException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("line 4").doesNotContain("\n"));
    }

    @Test
    void aValueOfTheWrongShapeIsReported() {
        assertThatThrownBy(() -> parse("fluxnova-workers:\n  max-tasks: many\n")).isInstanceOf(EngineConfigException.class);
    }

    private static TenantWorkerProperties.Tenant tenant(String id) {
        TenantWorkerProperties.Tenant tenant = new TenantWorkerProperties.Tenant();
        tenant.setId(id);
        tenant.setBaseUrl("http://h:8080/engine-rest/engine/" + id);
        return tenant;
    }
}
