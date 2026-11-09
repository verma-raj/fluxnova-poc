package com.example.fluxnova.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantConfigLoaderTest {

    private static final String VALID = """
            fluxnova:
              cluster:
                host: "shared-postgres.internal"
                port: 5432
                admin-user: "postgres_admin"
                admin-password: "secure_password"
              tenants:
                - id: "tenant-a"
                  dbName: "fluxnova_tenant_a"
                - id: "tenant-b"
                  dbName: "fluxnova_tenant_b"
            """;

    private final RuntimeProperties runtime = new RuntimeProperties();
    private final TenantConfigLoader loader = new TenantConfigLoader(runtime);

    private TenantConfigProperties parse(String yaml) {
        return loader.parse(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesTheDocumentedStructure() {
        TenantConfigProperties.Fluxnova root = parse(VALID).getFluxnova();

        assertThat(root.getCluster().getHost()).isEqualTo("shared-postgres.internal");
        assertThat(root.getCluster().getPort()).isEqualTo(5432);
        assertThat(root.getCluster().getAdminUser()).isEqualTo("postgres_admin");
        assertThat(root.getCluster().getAdminPassword()).isEqualTo("secure_password");
        assertThat(root.getCluster().getAdminDatabase()).isEqualTo("postgres");
        assertThat(root.getTenants()).extracting(TenantConfigProperties.Tenant::getId)
                .containsExactly("tenant-a", "tenant-b");
        assertThat(root.getTenants()).extracting(TenantConfigProperties.Tenant::getDbName)
                .containsExactly("fluxnova_tenant_a", "fluxnova_tenant_b");
    }

    @Test
    void camelCaseKeysAreAcceptedToo() {
        String yaml = VALID.replace("admin-user", "adminUser").replace("admin-password", "adminPassword");

        assertThat(parse(yaml).getFluxnova().getCluster().getAdminUser()).isEqualTo("postgres_admin");
    }

    @Test
    void utf8ByteOrderMarkFromWindowsEditorsIsTolerated() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        out.write(VALID.getBytes(StandardCharsets.UTF_8));

        assertThat(loader.parse(out.toByteArray()).getFluxnova().getTenants()).hasSize(2);
    }

    @Test
    void windowsLineEndingsAreTolerated() {
        assertThat(parse(VALID.replace("\n", "\r\n")).getFluxnova().getTenants()).hasSize(2);
    }

    @Test
    void adminPasswordFromEnvironmentOverridesTheFile() {
        runtime.setAdminPassword("from-secrets-manager");

        assertThat(parse(VALID).getFluxnova().getCluster().getAdminPassword()).isEqualTo("from-secrets-manager");
    }

    @Test
    void adminPasswordMayBeOmittedFromTheFileWhenProvidedByTheEnvironment() {
        runtime.setAdminPassword("from-secrets-manager");
        String yaml = VALID.replace("    admin-password: \"secure_password\"\n", "");

        assertThat(parse(yaml).getFluxnova().getCluster().getAdminPassword()).isEqualTo("from-secrets-manager");
    }

    @Test
    void emptyTenantListIsValid() {
        String yaml = VALID.substring(0, VALID.indexOf("  tenants:")) + "  tenants:\n";

        assertThat(parse(yaml).getFluxnova().getTenants()).isEmpty();
    }

    @Test
    void passwordIsNotPartOfToString() {
        assertThat(parse(VALID).toString()).doesNotContain("secure_password");
    }

    @Test
    void rejectsSqlInjectionInDatabaseName() {
        String yaml = VALID.replace("fluxnova_tenant_a", "x\\\"; DROP DATABASE prod; --");

        assertThatThrownBy(() -> parse(yaml)).isInstanceOf(TenantConfigException.class)
                .hasMessageContaining("tenants[0].dbName must match");
    }

    @Test
    void rejectsUpperCaseOrReservedOrDuplicateDatabaseNames() {
        assertThatThrownBy(() -> parse(VALID.replace("fluxnova_tenant_a", "Fluxnova_A")))
                .hasMessageContaining("tenants[0].dbName must match");
        assertThatThrownBy(() -> parse(VALID.replace("fluxnova_tenant_a", "postgres")))
                .hasMessageContaining("reserved system database");
        assertThatThrownBy(() -> parse(VALID.replace("fluxnova_tenant_b", "fluxnova_tenant_a")))
                .hasMessageContaining("used by more than one tenant");
    }

    @Test
    void rejectsBadOrDuplicateTenantIds() {
        assertThatThrownBy(() -> parse(VALID.replace("tenant-a", "tenant a/../x")))
                .hasMessageContaining("tenants[0].id must match");
        assertThatThrownBy(() -> parse(VALID.replace("tenant-b", "tenant-a")))
                .hasMessageContaining("used more than once");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        String yaml = VALID.replace("host: \"shared-postgres.internal\"", "host: \"bad host!\"")
                .replace("fluxnova_tenant_a", "BAD");

        assertThatThrownBy(() -> parse(yaml)).isInstanceOf(TenantConfigException.class)
                .hasMessageContaining("cluster.host")
                .hasMessageContaining("tenants[0].dbName");
    }

    @Test
    void rejectsMissingClusterOrPassword() {
        String noCluster = "fluxnova:\n  tenants:\n    - id: a\n      dbName: db_a\n";
        assertThatThrownBy(() -> parse(noCluster)).hasMessageContaining("fluxnova.cluster is missing");

        String noPassword = VALID.replace("    admin-password: \"secure_password\"\n", "");
        assertThatThrownBy(() -> parse(noPassword)).hasMessageContaining("admin-password is missing");
    }

    @Test
    void rejectsUnknownKeysWithoutEchoingTheSecretLine() {
        String yaml = VALID.replace("admin-user", "adminUsr");

        assertThatThrownBy(() -> parse(yaml)).isInstanceOf(TenantConfigException.class)
                .hasMessageContaining("adminUsr")
                .hasMessageContaining("line")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secure_password"));
    }

    @Test
    void rejectsDuplicateKeys() {
        String yaml = VALID.replace("    port: 5432\n", "    port: 5432\n    port: 5433\n");

        assertThatThrownBy(() -> parse(yaml)).isInstanceOf(TenantConfigException.class);
    }

    @Test
    void rejectsEmptyOrWrongShapedDocuments() {
        assertThatThrownBy(() -> loader.parse(new byte[0])).hasMessageContaining("empty");
        assertThatThrownBy(() -> parse("# only a comment\n")).hasMessageContaining("no 'fluxnova:' root");
        assertThatThrownBy(() -> parse("- a\n- b\n")).isInstanceOf(TenantConfigException.class);
        assertThatThrownBy(() -> parse("fluxnova: [unclosed")).isInstanceOf(TenantConfigException.class);
    }

    @Test
    void doesNotInstantiateArbitraryClassesFromYamlTags() {
        String yaml = "!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1/\"]]]]\n";

        assertThatThrownBy(() -> parse(yaml)).isInstanceOf(TenantConfigException.class);
    }
}
