package com.example.isaworker;

import com.example.isaworker.config.EngineConfigReader;
import com.example.isaworker.worker.MultiTenantWorkerOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The blank-slate promise: with no usable tenant configuration the application starts, warns, stays up and reports UP.
 * Each case starts the real application with the external config pointing at a different kind of "empty".
 */
@ExtendWith(OutputCaptureExtension.class)
class ZeroTenantStartupTest {

    @TempDir
    Path directory;

    private ConfigurableApplicationContext start(Path externalConfig) {
        return new SpringApplicationBuilder(IsaWorkerApplication.class).run(
                "--FLUXNOVA_WORKERS_CONFIG=" + externalConfig.toString().replace('\\', '/'),
                "--server.port=0", "--spring.main.banner-mode=off");
    }

    /** The configuration is read by the reader as the application starts; this also waits for the workers to be set up. */
    private static MultiTenantWorkerOrchestrator.Status settled(ConfigurableApplicationContext context) {
        MultiTenantWorkerOrchestrator orchestrator = context.getBean(MultiTenantWorkerOrchestrator.class);
        await().atMost(Duration.ofSeconds(20)).until(() -> orchestrator.status().initialised());
        return orchestrator.status();
    }

    private static void assertIdleAndHealthy(ConfigurableApplicationContext context, CapturedOutput output) {
        MultiTenantWorkerOrchestrator.Status status = settled(context);
        assertThat(status.idle()).isTrue();
        assertThat(status.skipped()).isEmpty();
        assertThat(output).contains("No active Fluxnova engine configured. Application is running in idle mode.");
        // Idle is a healthy state: the container must not be restarted or killed for it.
        assertThat(context.getBean(HealthEndpoint.class).health().getStatus()).isEqualTo(Status.UP);
        assertThat(context.isRunning()).isTrue();
    }

    @Test
    void theExternalFileIsMissing(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start(directory.resolve("does-not-exist.yml"))) {
            assertIdleAndHealthy(context, output);
            assertThat(context.getBean(EngineConfigReader.class).status().fileFound()).isFalse();
        }
    }

    @ParameterizedTest(name = "external file content: {0}")
    @ValueSource(strings = {
            "",                                                      // 0 bytes
            "\n\n",                                                  // only blank lines
            "# only a comment\n",                                    // only comments
            "fluxnova-workers:\n",                                   // section without content
            "fluxnova-workers:\n  tenants:\n",                       // tenants: null
            "fluxnova-workers:\n  tenants: []\n"                     // explicit empty list
    })
    void theExternalFileHasNoTenants(String content, CapturedOutput output) throws IOException {
        Path file = directory.resolve("fluxnova-engine.yml");
        Files.writeString(file, content);
        try (ConfigurableApplicationContext context = start(file)) {
            assertIdleAndHealthy(context, output);
            // An empty file is a LEGITIMATE "no tenants", not a broken one: it must not be reported as invalid.
            assertThat(output).doesNotContain("Ignoring invalid");
            assertThat(context.getBean(EngineConfigReader.class).status().error()).isNull();
        }
    }

    @Test
    void everyEntryIsUnusableSoTheApplicationIdlesAndSaysWhyForEach(CapturedOutput output) throws IOException {
        Path file = directory.resolve("fluxnova-engine.yml");
        Files.writeString(file, """
                fluxnova-workers:
                  tenants:
                    - id: "no-url"
                    - baseUrl: "http://localhost:1/engine-rest/engine/no-id"
                    - id: "not-a-url"
                      baseUrl: "this is not a url"
                    - id: "wrong-scheme"
                      baseUrl: "ftp://host/engine-rest/engine/wrong-scheme"
                """);
        try (ConfigurableApplicationContext context = start(file)) {
            MultiTenantWorkerOrchestrator.Status status = settled(context);
            assertThat(status.idle()).isTrue();
            assertThat(status.skipped()).hasSize(4);
            assertThat(output).contains("Tenant 'no-url' is skipped").contains("Tenant #2 is skipped")
                    .contains("Tenant 'not-a-url' is skipped").contains("Tenant 'wrong-scheme' is skipped")
                    .contains("None of the 4 configured tenant entries could be started")
                    .contains("No active Fluxnova engine configured. Application is running in idle mode.");
            assertThat(context.getBean(HealthEndpoint.class).health().getStatus()).isEqualTo(Status.UP);
        }
    }

    @Test
    void aFileThatIsInvalidAtStartUpStartsIdleInsteadOfNotAtAll(CapturedOutput output) throws IOException {
        Path file = directory.resolve("fluxnova-engine.yml");
        Files.writeString(file, "fluxnova-workers:\n  tenants:\n   - id: \"tenant-x\"\n      baseUrl: \"http://h/e\"\n");  // bad indent
        try (ConfigurableApplicationContext context = start(file)) {
            assertIdleAndHealthy(context, output);
            assertThat(output).contains("Ignoring invalid");
            // The reason is not only in the log: the health details carry it (config.error).
            EngineConfigReader.ConfigStatus status = context.getBean(EngineConfigReader.class).status();
            assertThat(status.fileFound()).isTrue();
            assertThat(status.error()).isNotBlank();
        }
    }

    @Test
    void theJarAloneWithoutAnyExternalConfigurationStartsIdle(CapturedOutput output) {
        // No FLUXNOVA_WORKERS_CONFIG at all: the default location /config/fluxnova-engine.yml does not exist here.
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(IsaWorkerApplication.class)
                .run("--server.port=0", "--spring.main.banner-mode=off")) {
            assertIdleAndHealthy(context, output);
        }
    }
}
