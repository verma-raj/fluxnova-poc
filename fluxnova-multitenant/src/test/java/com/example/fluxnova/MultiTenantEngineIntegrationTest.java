package com.example.fluxnova;

import com.example.fluxnova.config.RuntimeProperties;
import com.example.fluxnova.engine.FluxnovaEngineManager;
import com.example.fluxnova.engine.TenantEngine;
import com.example.fluxnova.portal.TenantEngineDirectory;
import com.example.fluxnova.watch.TenantConfigWatcher;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.finos.fluxnova.bpm.engine.ProcessEngines;
import org.finos.fluxnova.bpm.engine.identity.User;
import org.finos.fluxnova.bpm.engine.task.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the whole application against a real PostgreSQL server started in-process, and drives it the way an operator
 * would: by editing tenants.yml while the application is running. The tests build on each other (ordered).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiTenantEngineIntegrationTest {

    private static final Duration LONG = Duration.ofSeconds(120);

    private static final EmbeddedPostgres POSTGRES;
    private static final Path CONFIG_FILE;
    /** The deploy-and-run folder: .bpmn files that land here are deployed to the tenants and started. */
    private static final Path BPMN_DIR;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
            CONFIG_FILE = Files.createTempDirectory("fluxnova-config").resolve("tenants.yml");
            BPMN_DIR = Files.createTempDirectory("fluxnova-bpmn");
            writeConfig("postgres", "tenant-a");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fluxnova.runtime.config-path", CONFIG_FILE::toString);
        registry.add("fluxnova.runtime.poll-interval", () -> "PT1S");
        registry.add("fluxnova.runtime.bpmn-deployment.directory", BPMN_DIR::toString);
        registry.add("fluxnova.runtime.bpmn-deployment.poll-interval", () -> "PT1S");
        registry.add("fluxnova.runtime.retry-initial-delay", () -> "PT1S");
        registry.add("fluxnova.runtime.web.admin-password", () -> ADMIN_PASSWORD);
        registry.add("fluxnova.runtime.offboarding.grace-period", () -> "PT5S");
        // The default is to allow removing the last tenant. The tests start with the protection switched ON to prove it
        // works, and switch it to the default near the end (see removingEveryTenant...).
        registry.add("fluxnova.runtime.offboarding.allow-remove-all", () -> "false");
    }

    private static final String ADMIN_PASSWORD = "Test#Passw0rd!2026";

    @LocalServerPort
    private int port;
    @Autowired
    private FluxnovaEngineManager manager;
    @Autowired
    private TenantConfigWatcher watcher;
    @Autowired
    private RuntimeProperties runtime;
    @Autowired
    private MockMvc mvc;

    private ProcessEngine tenantAEngineBeforeOnboardingB;
    private String approvalInstanceInTenantA;

    @AfterAll
    void tearDown() throws IOException {
        manager.shutdown();     // close engines while the database is still there
        POSTGRES.close();
    }

    @Test
    @Order(1)
    void tenantInConfigFileIsOnboardedAtStartup_databaseAndSchemaAreCreated() throws Exception {
        await().atMost(LONG).until(() -> manager.find("tenant-a").isPresent());

        TenantEngine tenantA = manager.find("tenant-a").orElseThrow();
        assertThat(tenantA.isDatabaseCreated()).isTrue();
        assertThat(tenantA.getDbName()).isEqualTo("fluxnova_tenant_a");
        assertThat(tenantA.getDataSource().getPoolName()).isEqualTo("fluxnova-pool-tenant-a");

        assertThat(queryStrings(POSTGRES.getPostgresDatabase().getConnection(),
                "SELECT datname FROM pg_database")).contains("fluxnova_tenant_a");
        // Fluxnova's own tables exist in the tenant's database: DB_SCHEMA_UPDATE_TRUE initialised the schema.
        assertThat(queryStrings(POSTGRES.getDatabase("postgres", "fluxnova_tenant_a").getConnection(),
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"))
                .contains("act_ru_execution", "act_re_procdef", "act_ge_property");
        assertThat(ProcessEngines.getProcessEngine("tenant-a")).isSameAs(tenantA.getEngine());
    }

    @Test
    @Order(2)
    void tenantEngineRunsProcessesIncludingTimerJobsOnItsOwnJobExecutor() {
        ProcessEngine engine = manager.find("tenant-a").orElseThrow().getEngine();
        deploy(engine, "approval.bpmn", APPROVAL_PROCESS);
        deploy(engine, "timer.bpmn", TIMER_PROCESS);

        // Thread dumps show which tenant a job executor / pool thread belongs to.
        assertThat(threadNames()).contains("fluxnova-jobs-tenant-a");

        approvalInstanceInTenantA = engine.getRuntimeService().startProcessInstanceByKey("approval").getId();
        engine.getRuntimeService().startProcessInstanceByKey("timerDemo");

        // The timer (PT1S) can only fire if this tenant's job executor acquires and executes the job.
        await().atMost(Duration.ofSeconds(60)).until(() ->
                engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey("timerDemo").count() == 0);
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery()
                .processDefinitionKey("timerDemo").finished().count()).isEqualTo(1);
        assertThat(engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey("approval").count())
                .isEqualTo(1);

        tenantAEngineBeforeOnboardingB = engine;
    }

    @Test
    @Order(3)
    void addingATenantToTheFileOnboardsItWhileTheRunningTenantIsUntouched() throws Exception {
        writeConfig("postgres", "tenant-a", "tenant-b");

        await().atMost(LONG).until(() -> manager.find("tenant-b").isPresent());

        // tenant-a: same engine object (not rebuilt), process instance still running and still completable.
        TenantEngine tenantA = manager.find("tenant-a").orElseThrow();
        assertThat(tenantA.getEngine()).isSameAs(tenantAEngineBeforeOnboardingB);
        assertThat(tenantA.getEngine().getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(approvalInstanceInTenantA).count()).isEqualTo(1);
        Task task = tenantA.getEngine().getTaskService().createTaskQuery()
                .processInstanceId(approvalInstanceInTenantA).singleResult();
        tenantA.getEngine().getTaskService().complete(task.getId());
        assertThat(tenantA.getEngine().getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(approvalInstanceInTenantA).count()).isZero();

        // tenant-b: own database, own pool, own engine, and none of tenant-a's data.
        TenantEngine tenantB = manager.find("tenant-b").orElseThrow();
        assertThat(tenantB.getDbName()).isEqualTo("fluxnova_tenant_b");
        assertThat(tenantB.getEngine()).isNotSameAs(tenantA.getEngine());
        assertThat(tenantB.getDataSource()).isNotSameAs(tenantA.getDataSource());
        assertThat(tenantB.getEngine().getRepositoryService().createDeploymentQuery().count()).isZero();
        assertThat(tenantB.getEngine().getHistoryService().createHistoricProcessInstanceQuery().count()).isZero();

        deploy(tenantB.getEngine(), "approval.bpmn", APPROVAL_PROCESS);
        tenantB.getEngine().getRuntimeService().startProcessInstanceByKey("approval");
        assertThat(tenantB.getEngine().getRuntimeService().createProcessInstanceQuery().count()).isEqualTo(1);
        assertThat(tenantA.getEngine().getRuntimeService().createProcessInstanceQuery()
                .processDefinitionKey("approval").count()).isZero();

        assertThat(ProcessEngines.getProcessEngine("tenant-a")).isSameAs(tenantA.getEngine());
        assertThat(ProcessEngines.getProcessEngine("tenant-b")).isSameAs(tenantB.getEngine());
        assertThat(queryStrings(POSTGRES.getPostgresDatabase().getConnection(), "SELECT datname FROM pg_database"))
                .contains("fluxnova_tenant_a", "fluxnova_tenant_b");
    }

    @Test
    @Order(4)
    void invalidConfigFileIsIgnoredAndTheNextValidOneIsApplied() throws Exception {
        writeRaw("fluxnova:\n  cluster: [this is not valid\n");

        await().atMost(LONG).until(() -> watcher.status().lastError() != null);
        assertThat(manager.activeTenants()).extracting(TenantEngine::getTenantId).containsExactly("tenant-a", "tenant-b");

        writeConfig("postgres", "tenant-a", "tenant-b", "tenant-c");

        await().atMost(LONG).until(() -> manager.find("tenant-c").isPresent());
        // The status is published when the watcher's run has finished, which is shortly after the engine appeared.
        await().atMost(LONG).until(() -> watcher.status().lastError() == null);
        assertThat(manager.activeTenants()).extracting(TenantEngine::getTenantId)
                .containsExactly("tenant-a", "tenant-b", "tenant-c");
    }

    @Test
    @Order(5)
    void failedOnboardingIsReportedAndRetriedUntilItSucceeds() throws Exception {
        // A cluster user that may log in but may not create databases: onboarding of a NEW tenant must fail.
        try (Connection c = POSTGRES.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE ROLE limited LOGIN");
            // The tenant registry table was created by the first administrator; the new one is allowed to use it too.
            st.execute("GRANT ALL ON TABLE fluxnova_tenant_registry TO limited");
        }
        writeConfig("limited", "tenant-a", "tenant-b", "tenant-c", "tenant-d");

        await().atMost(LONG).until(() -> manager.failedTenants().stream().anyMatch(f -> f.tenantId().equals("tenant-d")));
        assertThat(manager.find("tenant-d")).isEmpty();
        assertThat(manager.failedTenants().get(0).lastError()).containsIgnoringCase("permission denied");
        // The healthy tenants were not affected by the failure of the new one.
        assertThat(manager.activeTenants()).extracting(TenantEngine::getTenantId)
                .containsExactly("tenant-a", "tenant-b", "tenant-c");
        mvc.perform(MockMvcRequestBuilders.get("/api/tenants/tenant-d")).andExpect(status().isServiceUnavailable());

        // Fix the cause on the database side only - the config file is NOT touched. The retry has to pick it up.
        try (Connection c = POSTGRES.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER ROLE limited CREATEDB");
        }

        await().atMost(LONG).until(() -> manager.find("tenant-d").isPresent());
        assertThat(manager.failedTenants()).isEmpty();
        assertThat(manager.find("tenant-d").orElseThrow().getEngine().getRepositoryService().createDeploymentQuery().count())
                .isZero();
    }

    @Test
    @Order(6)
    void managementEndpointListsEnginesWithoutLeakingCredentials() throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.get("/api/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount").value(4))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.active[*].tenantId").value(
                        org.hamcrest.Matchers.contains("tenant-a", "tenant-b", "tenant-c", "tenant-d")))
                .andExpect(jsonPath("$.active[0].dbName").value("fluxnova_tenant_a"))
                .andExpect(jsonPath("$.active[0].state").value("RUNNING"))
                .andExpect(jsonPath("$.active[0].jobExecutorActive").value(true))
                .andExpect(jsonPath("$.active[0].pool.maximum").value(10))
                .andExpect(jsonPath("$.config.fileFound").value(true))
                // The cluster the engines are connected to, with the server's own version (the test server is the same
                // PostgreSQL major version as the production cluster container, postgres:16.15).
                .andExpect(jsonPath("$.cluster.host").value("localhost"))
                .andExpect(jsonPath("$.cluster.port").value(POSTGRES.getPort()))
                .andExpect(jsonPath("$.cluster.serverVersion").value(org.hamcrest.Matchers.startsWith("16.")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContainIgnoringCase("password").doesNotContain("limited");

        mvc.perform(MockMvcRequestBuilders.get("/api/tenants/tenant-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.processDefinitions").value(2))
                .andExpect(jsonPath("$.runningProcessInstances").value(0));
        mvc.perform(MockMvcRequestBuilders.get("/api/tenants/tenant-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processDefinitions").value(1))
                .andExpect(jsonPath("$.runningProcessInstances").value(1));
        mvc.perform(MockMvcRequestBuilders.get("/api/tenants/does-not-exist")).andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @Order(7)
    void webPortalServesEveryEngine_oneAdministratorLoginCoversAllEngines() throws Exception {
        HttpClient anonymous = HttpClient.newHttpClient();
        for (String tenant : List.of("tenant-a", "tenant-b", "tenant-c", "tenant-d")) {
            for (String app : List.of("welcome", "monitoring", "tasklist", "admin")) {
                assertThat(send(anonymous, get("/fluxnova/app/" + app + "/" + tenant + "/")).statusCode())
                        .as("%s app of %s", app, tenant).isEqualTo(200);
            }
        }

        CookieManager cookies = new CookieManager();
        HttpClient browser = browser(cookies);
        HttpResponse<String> login = portalLogin(browser, cookies, "tenant-a", "admin", ADMIN_PASSWORD, "monitoring");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("\"userId\":\"admin\"").contains("monitoring").contains("tasklist").contains("admin");

        // The session works for the engine that was logged in to ...
        HttpResponse<String> own = send(browser, get("/fluxnova/api/engine/engine/tenant-a/process-definition/count"));
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(own.body()).isEqualTo("{\"count\":2}");
        // ... and, because there is ONE login for all tenants, for every other engine too (each has its own data) ...
        assertThat(send(browser, get("/fluxnova/api/engine/engine/tenant-b/process-definition/count")).body()).isEqualTo("{\"count\":1}");
        assertThat(send(browser, get("/fluxnova/api/engine/engine/tenant-c/process-definition/count")).body()).isEqualTo("{\"count\":0}");
        assertThat(send(browser, get("/fluxnova/api/engine/engine/tenant-d/process-definition/count")).statusCode()).isEqualTo(200);
        // ... but never without a session.
        assertThat(send(anonymous, get("/fluxnova/api/engine/engine/tenant-a/process-definition/count")).statusCode()).isEqualTo(401);
        assertThat(send(anonymous, get("/fluxnova/api/engine/engine/tenant-b/process-definition/count")).statusCode()).isEqualTo(401);

        // Every engine has its own administrator in its own database.
        for (String tenant : List.of("tenant-a", "tenant-b", "tenant-c", "tenant-d")) {
            ProcessEngine engine = manager.find(tenant).orElseThrow().getEngine();
            assertThat(engine.getIdentityService().createUserQuery().userId("admin").count()).isEqualTo(1);
            assertThat(engine.getIdentityService().createUserQuery().userId("admin").memberOfGroup("fluxnova-admin").count())
                    .isEqualTo(1);
        }
    }

    @Test
    @Order(8)
    void onlyAdministratorsCanLogInToThePortal() throws Exception {
        IdentityService identity = manager.find("tenant-c").orElseThrow().getEngine().getIdentityService();
        User bob = identity.newUser("bob");            // an ordinary user: not in the fluxnova-admin group
        bob.setPassword("Bob#Passw0rd!2026");
        identity.saveUser(bob);

        CookieManager cookies = new CookieManager();
        HttpClient browser = browser(cookies);
        HttpResponse<String> refused = portalLogin(browser, cookies, "tenant-c", "bob", "Bob#Passw0rd!2026", "tasklist");
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("Only administrators");
        // The answer is the same for a user that does not exist at all, so it reveals nothing.
        assertThat(portalLogin(browser, cookies, "tenant-c", "nobody-at-all", "x", "tasklist").statusCode()).isEqualTo(403);
        assertThat(refused.body()).isEqualTo(portalLogin(browser, cookies, "tenant-c", "nobody-at-all", "x", "tasklist").body());
        // He has no session ...
        assertThat(send(browser, get("/fluxnova/api/engine/engine/tenant-c/process-definition/count")).statusCode()).isEqualTo(401);
        // ... but a service account like him can still use the REST API, within the permissions it has.
        assertThat(send(HttpClient.newHttpClient(), basic(get("/engine-rest/engine/tenant-c/process-definition/count"), "bob", "Bob#Passw0rd!2026"))
                .statusCode()).isEqualTo(200);

        // Making him an administrator is what lets him in.
        identity.createMembership("bob", "fluxnova-admin");
        assertThat(portalLogin(browser, cookies, "tenant-c", "bob", "Bob#Passw0rd!2026", "tasklist").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(9)
    void landingPageIsNeutral_administratorsSignInOnce_andChooseATenant() throws Exception {
        CookieManager cookies = new CookieManager();
        HttpClient browser = browser(cookies);

        // Nothing is preselected: the site root offers a sign-in, not any tenant's login, and names no tenant.
        HttpResponse<String> home = send(browser, get("/"));
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("Fluxnova admin console", "Sign in")
                .doesNotContain("tenant-a").doesNotContain("tenant-b").doesNotContain("fluxnova_tenant");
        assertThat(home.headers().firstValue("Content-Security-Policy")).isPresent();
        assertThat(home.headers().firstValue("Cache-Control")).contains("no-store");

        // Portal addresses that name no tenant lead back to it instead of into one tenant ...
        for (String address : List.of("/fluxnova/", "/fluxnova/app/", "/fluxnova/app/welcome/", "/fluxnova/app/tasklist/")) {
            HttpResponse<String> redirect = send(browser, get(address));
            assertThat(redirect.statusCode()).as(address).isEqualTo(302);
            assertThat(redirect.headers().firstValue("Location").orElse("")).as(address).endsWith("/").doesNotContain("/app/");
        }
        // ... while an address that names a tenant is left alone.
        assertThat(send(browser, get("/fluxnova/app/welcome/tenant-a/")).statusCode()).isEqualTo(200);

        String csrf = csrfOf(home.body());
        // Refused: a forged form (no valid token), a wrong password, and a user who is not an administrator.
        IdentityService tenantB = manager.find("tenant-b").orElseThrow().getEngine().getIdentityService();
        User eve = tenantB.newUser("eve");                       // an ordinary user: not in the fluxnova-admin group
        eve.setPassword("Eve#Passw0rd!2026");
        tenantB.saveUser(eve);
        assertThat(landingLogin(browser, "admin", ADMIN_PASSWORD, "forged-token").headers().firstValue("Location").orElse("")).contains("error");
        assertThat(landingLogin(browser, "bob", "wrong-password", csrf).headers().firstValue("Location").orElse("")).contains("error");
        assertThat(landingLogin(browser, "eve", "Eve#Passw0rd!2026", csrf).headers().firstValue("Location").orElse("")).contains("error");
        assertThat(send(browser, get("/?error=1")).body()).contains("Sign-in failed").doesNotContain("tenant-a");
        assertThat(send(browser, get("/fluxnova/api/engine/engine/tenant-a/process-definition/count")).statusCode()).isEqualTo(401);

        // An administrator signs in once ...
        HttpResponse<String> signedIn = landingLogin(browser, "admin", ADMIN_PASSWORD, csrf);
        assertThat(signedIn.statusCode()).isEqualTo(303);
        assertThat(signedIn.headers().firstValue("Location")).contains("/");
        HttpResponse<String> console = send(browser, get("/"));
        assertThat(console.body()).contains("Signed in as", "<strong>admin</strong>", "Tenants")
                .contains("/fluxnova/app/monitoring/tenant-a/", "/fluxnova/app/tasklist/tenant-b/", "/fluxnova/app/admin/tenant-c/",
                        "fluxnova_tenant_d");
        // ... and that one sign-in is a sign-in to every tenant: the portal answers each of them for the same session.
        for (String tenant : List.of("tenant-a", "tenant-b", "tenant-c", "tenant-d")) {
            assertThat(send(browser, get("/fluxnova/api/engine/engine/" + tenant + "/process-definition/count")).statusCode())
                    .as("portal API of %s", tenant).isEqualTo(200);
        }

        // Signing out needs the form token, and ends the sign-in to all tenants at once.
        String logoutCsrf = csrfOf(console.body());
        send(browser, post("/landing/logout", "csrf=forged-token", "application/x-www-form-urlencoded"));
        assertThat(send(browser, get("/")).body()).contains("Signed in as");
        assertThat(send(browser, post("/landing/logout", "csrf=" + URLEncoder.encode(logoutCsrf, StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded")).statusCode()).isEqualTo(303);
        assertThat(send(browser, get("/")).body()).contains("Sign in").doesNotContain("tenant-a");
        assertThat(send(browser, get("/fluxnova/api/engine/engine/tenant-a/process-definition/count")).statusCode()).isEqualTo(401);
    }

    @Test
    @Order(10)
    void wrongCredentialsAreRejected_andTheTemporaryLoginDelayEndsByItself() {
        CookieManager cookies = new CookieManager();
        HttpClient browser = browser(cookies);
        // tenant-d is used for this on purpose: a failed attempt makes the engine delay that user for a few seconds.
        assertThat(portalLogin(browser, cookies, "tenant-d", "admin", "wrong-password", "tasklist").statusCode()).isEqualTo(401);
        // Not an administrator (here: does not exist): refused before the password is even looked at.
        assertThat(portalLogin(browser, cookies, "tenant-d", "nobody", ADMIN_PASSWORD, "tasklist").statusCode()).isEqualTo(403);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
                .until(() -> portalLogin(browser, cookies, "tenant-d", "admin", ADMIN_PASSWORD, "tasklist").statusCode() == 200);
    }

    @Test
    @Order(11)
    void restApiRequiresAuthentication_enforcesAuthorizations_andHasNoDefaultEngine() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String countA = "/engine-rest/engine/tenant-a/process-definition/count";

        assertThat(send(client, get(countA)).statusCode()).isEqualTo(401);
        // A wrong password makes the engine delay that user for a few seconds, so use an engine that is not used below.
        assertThat(send(client, basic(get("/engine-rest/engine/tenant-c/process-definition/count"), "admin", "wrong-password"))
                .statusCode()).isEqualTo(401);

        HttpResponse<String> a = send(client, basic(get(countA), "admin", ADMIN_PASSWORD));
        assertThat(a.statusCode()).isEqualTo(200);
        assertThat(a.body()).isEqualTo("{\"count\":2}");
        HttpResponse<String> b = send(client, basic(get("/engine-rest/engine/tenant-b/process-definition/count"), "admin", ADMIN_PASSWORD));
        assertThat(b.body()).isEqualTo("{\"count\":1}");

        HttpResponse<String> engines = send(client, basic(get("/engine-rest/engine"), "admin", ADMIN_PASSWORD));
        assertThat(engines.body()).contains("tenant-a", "tenant-b", "tenant-c", "tenant-d");

        // No route may silently act on "some default" tenant.
        assertThat(send(client, basic(get("/engine-rest/process-definition/count"), "admin", ADMIN_PASSWORD)).statusCode())
                .isEqualTo(404);

        // An administrator can start a process through the REST API ...
        HttpResponse<String> started = send(client, basic(post("/engine-rest/engine/tenant-b/process-definition/key/approval/start",
                "{}", "application/json"), "admin", ADMIN_PASSWORD));
        assertThat(started.statusCode()).isEqualTo(200);
        assertThat(manager.find("tenant-b").orElseThrow().getEngine().getRuntimeService().createProcessInstanceQuery().count())
                .isEqualTo(2);

        // ... a user without any authorization cannot: authorizations are enforced.
        IdentityService identity = manager.find("tenant-b").orElseThrow().getEngine().getIdentityService();
        User alice = identity.newUser("alice");
        alice.setPassword("Alice#Passw0rd!2026");
        identity.saveUser(alice);
        HttpResponse<String> denied = send(client, basic(post("/engine-rest/engine/tenant-b/process-definition/key/approval/start",
                "{}", "application/json"), "alice", "Alice#Passw0rd!2026"));
        // 403 or 404: without READ permission the engine does not even reveal that the definition exists.
        assertThat(denied.statusCode()).isIn(403, 404);
        assertThat(manager.find("tenant-b").orElseThrow().getEngine().getRuntimeService().createProcessInstanceQuery().count())
                .isEqualTo(2);
        // And she sees none of the definitions the administrator sees.
        HttpResponse<String> aliceCount = send(client, basic(get("/engine-rest/engine/tenant-b/process-definition/count"),
                "alice", "Alice#Passw0rd!2026"));
        assertThat(aliceCount.statusCode()).isEqualTo(200);
        assertThat(aliceCount.body()).isEqualTo("{\"count\":0}");
    }

    // ---- offboarding: a tenant removed from tenants.yml loses its engine AND its database ------------------------------

    @Test
    @Order(12)
    void removingATenantFromTheFileOffboardsIt_engineStopped_databaseDropped_othersUntouched() throws Exception {
        TenantEngine a = manager.find("tenant-a").orElseThrow();
        TenantEngine b = manager.find("tenant-b").orElseThrow();
        TenantEngine c = manager.find("tenant-c").orElseThrow();
        assertThat(databases()).contains("fluxnova_tenant_c");
        // tenant-c holds data: the user "bob" from the portal test lives in its database
        assertThat(c.getEngine().getIdentityService().createUserQuery().userId("bob").count()).isEqualTo(1);
        // Every running tenant is recorded in the cluster's registry (this is what makes later cleanup restart-proof).
        await().atMost(LONG).until(() -> registeredTenants().containsAll(List.of("tenant-a", "tenant-b", "tenant-c", "tenant-d")));

        writeConfig("limited", "tenant-a", "tenant-b", "tenant-d");           // tenant-c is removed

        // It is only scheduled at first: it keeps running during the grace period, and the API says when it will go.
        await().atMost(LONG).until(() -> watcher.scheduledOffboardings().stream().anyMatch(s -> s.tenantId().equals("tenant-c")));
        assertThat(watcher.scheduledOffboardings()).extracting(TenantConfigWatcher.ScheduledOffboarding::dbName)
                .contains("fluxnova_tenant_c");
        assertThat(manager.find("tenant-c")).isPresent();

        await().atMost(LONG).until(() -> manager.find("tenant-c").isEmpty());
        await().atMost(LONG).until(() -> !databases().contains("fluxnova_tenant_c"));

        // Engine and pool are gone ...
        assertThat(c.getDataSource().isClosed()).isTrue();
        assertThat(ProcessEngines.getProcessEngines()).doesNotContainKey("tenant-c");
        assertThat(TenantEngineDirectory.engines()).doesNotContainKey("tenant-c");
        assertThat(send(HttpClient.newHttpClient(), basic(get("/engine-rest/engine"), "admin", ADMIN_PASSWORD)).body())
                .doesNotContain("tenant-c").contains("tenant-a", "tenant-b", "tenant-d");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(threadNames())
                .noneMatch(name -> name.equals("fluxnova-jobs-tenant-c") || name.startsWith("fluxnova-pool-tenant-c")));
        assertThat(watcher.scheduledOffboardings()).isEmpty();
        assertThat(manager.pendingDatabaseDrops()).isEmpty();
        // ... the registry forgot it, and still knows the others.
        await().atMost(LONG).until(() -> !registeredTenants().contains("tenant-c"));
        assertThat(registeredTenants()).contains("tenant-a", "tenant-b", "tenant-d");

        // ... and nothing else was touched: same engines, same databases.
        assertThat(manager.find("tenant-a").orElseThrow().getEngine()).isSameAs(a.getEngine());
        assertThat(manager.find("tenant-b").orElseThrow().getEngine()).isSameAs(b.getEngine());
        assertThat(databases()).contains("fluxnova_tenant_a", "fluxnova_tenant_b", "fluxnova_tenant_d");
        assertThat(a.getEngine().getRepositoryService().createDeploymentQuery().count()).isEqualTo(2);

        // The data is really gone: onboarding the same tenant again starts from an empty database.
        writeConfig("limited", "tenant-a", "tenant-b", "tenant-c", "tenant-d");
        await().atMost(LONG).until(() -> manager.find("tenant-c").isPresent());
        TenantEngine again = manager.find("tenant-c").orElseThrow();
        assertThat(again.isDatabaseCreated()).isTrue();
        assertThat(again.getEngine().getIdentityService().createUserQuery().userId("bob").count()).isZero();
    }

    @Test
    @Order(13)
    void aTenantAddedBackDuringTheGracePeriodIsNotOffboarded() throws Exception {
        TenantEngine d = manager.find("tenant-d").orElseThrow();

        writeConfig("limited", "tenant-a", "tenant-b", "tenant-c");           // tenant-d removed by mistake ...
        await().atMost(LONG).until(() -> watcher.scheduledOffboardings().stream().anyMatch(s -> s.tenantId().equals("tenant-d")));
        writeConfig("limited", "tenant-a", "tenant-b", "tenant-c", "tenant-d"); // ... and put back before the grace period ends

        await().atMost(LONG).until(() -> watcher.scheduledOffboardings().isEmpty());
        // Stay quiet well past the grace period (5 s): nothing may happen to it.
        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(20))
                .until(() -> manager.find("tenant-d").isPresent() && databases().contains("fluxnova_tenant_d"));
        assertThat(manager.find("tenant-d").orElseThrow().getEngine()).isSameAs(d.getEngine());
    }

    @Test
    @Order(14)
    void removingEveryTenantAtOnceIsRefused_andAnInvalidFileNeverRemovesAnything() throws Exception {
        List<String> before = manager.activeTenants().stream().map(TenantEngine::getTenantId).toList();
        assertThat(before).containsExactly("tenant-a", "tenant-b", "tenant-c", "tenant-d");

        writeConfig("limited");                                                // an empty tenant list
        await().atMost(LONG).until(() -> watcher.status().tenantsConfigured() == 0);
        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(20)).until(() ->
                manager.activeTenants().size() == 4 && databases().contains("fluxnova_tenant_a"));
        assertThat(watcher.scheduledOffboardings()).isEmpty();

        writeRaw("fluxnova:\n  cluster: [this is not valid\n");              // a broken file
        await().atMost(LONG).until(() -> watcher.status().lastError() != null);
        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(20)).until(() -> manager.activeTenants().size() == 4);

        writeConfig("limited", "tenant-a", "tenant-b", "tenant-c", "tenant-d");   // back to the full list
        await().atMost(LONG).until(() -> watcher.status().lastError() == null && watcher.status().tenantsConfigured() == 4);
    }

    @Test
    @Order(15)
    void aFailedDatabaseDropIsRetriedUntilItSucceeds() throws Exception {
        // tenant-d's pool user ("limited") is made NOT the owner of its database, so DROP DATABASE must fail.
        try (Connection c = POSTGRES.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER DATABASE fluxnova_tenant_d OWNER TO postgres");
        }

        writeConfig("limited", "tenant-a", "tenant-b", "tenant-c");           // tenant-d removed for good
        await().atMost(LONG).until(() -> manager.find("tenant-d").isEmpty());  // the engine is stopped after the grace period ...
        await().atMost(LONG).until(() -> manager.pendingDatabaseDrops().stream().anyMatch(p -> p.tenantId().equals("tenant-d")));
        assertThat(databases()).contains("fluxnova_tenant_d");                // ... but the database could not be dropped yet
        assertThat(manager.pendingDatabaseDrops().get(0).lastError()).containsIgnoringCase("owner");

        try (Connection c = POSTGRES.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER DATABASE fluxnova_tenant_d OWNER TO limited");     // fix the cause; the config is not touched
        }

        await().atMost(LONG).until(() -> !databases().contains("fluxnova_tenant_d"));
        await().atMost(LONG).until(() -> manager.pendingDatabaseDrops().isEmpty());
        assertThat(manager.activeTenants()).extracting(TenantEngine::getTenantId).containsExactly("tenant-a", "tenant-b", "tenant-c");
        // The registry row of the offboarded tenant is cleaned up as well, once its database is really gone.
        await().atMost(LONG).until(() -> !registeredTenants().contains("tenant-d"));
    }

    @Test
    @Order(16)
    void aRegisteredDatabaseOfARemovedTenantIsDroppedEvenWithoutARunningEngine_otherDatabasesAreNeverTouched() throws Exception {
        // The state after an application restart in which a tenant was removed from the file meanwhile: its database and its
        // registry row exist, but no engine runs for it. Plus three databases that must never be dropped.
        try (Connection c = POSTGRES.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            // registered, tenant gone -> must be dropped. Owned by the administrator in tenants.yml, as it would be in real life.
            st.execute("CREATE DATABASE fluxnova_orphan_x OWNER limited");
            st.execute("CREATE DATABASE fluxnova_unregistered_y");      // the application never registered it -> untouched
            st.execute("CREATE DATABASE fluxnova_foreign_z");           // registered by ANOTHER runtime (scope) -> untouched
            st.execute("INSERT INTO fluxnova_tenant_registry (scope, tenant_id, db_name) VALUES "
                    + "('default', 'orphan-x', 'fluxnova_orphan_x'), "
                    + "('default', 'orphan-renamed', 'fluxnova_tenant_a'), "   // its database is now used by a listed tenant
                    + "('other-runtime', 'foreign', 'fluxnova_foreign_z')");
        }
        List<String> runningBefore = manager.activeTenants().stream().map(TenantEngine::getTenantId).toList();

        // Found without any engine, after the grace period kept in the registry, then dropped and forgotten.
        await().atMost(LONG).until(() -> watcher.scheduledOffboardings().stream().anyMatch(s -> s.tenantId().equals("orphan-x")));
        await().atMost(LONG).until(() -> !databases().contains("fluxnova_orphan_x"));
        await().atMost(LONG).until(() -> !registeredTenants().contains("orphan-x"));

        // A registered tenant whose database a listed tenant now uses is forgotten, but its database is KEPT.
        await().atMost(LONG).until(() -> !registeredTenants().contains("orphan-renamed"));
        assertThat(databases()).contains("fluxnova_tenant_a");

        // Not registered / not ours: never touched, however long we wait past the grace period.
        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(20)).until(() ->
                databases().containsAll(List.of("fluxnova_unregistered_y", "fluxnova_foreign_z")));
        assertThat(queryStrings(POSTGRES.getPostgresDatabase().getConnection(),
                "SELECT tenant_id FROM fluxnova_tenant_registry WHERE scope = 'other-runtime'")).containsExactly("foreign");

        assertThat(manager.activeTenants()).extracting(TenantEngine::getTenantId).containsExactlyElementsOf(runningBefore);
        try (Connection c = POSTGRES.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE fluxnova_unregistered_y");
            st.execute("DROP DATABASE fluxnova_foreign_z");
            st.execute("DELETE FROM fluxnova_tenant_registry WHERE scope = 'other-runtime'");
        }
    }

    @Test
    @Order(17)
    void removingEveryTenantOffboardsThem_andTheLandingPageStillOffersTheAdministratorASignIn() throws Exception {
        // The default behaviour: the LAST tenants can be removed like any other (the protection was on until now).
        assertThat(manager.activeTenants()).extracting(TenantEngine::getTenantId).containsExactly("tenant-a", "tenant-b", "tenant-c");
        runtime.getOffboarding().setAllowRemoveAll(true);

        writeConfig("limited");                                                        // every tenant removed
        await().atMost(LONG).until(() -> manager.activeTenants().isEmpty());
        await().atMost(LONG).until(() -> databases().stream().noneMatch(name -> name.startsWith("fluxnova_tenant_")));
        await().atMost(LONG).until(() -> registeredTenants().isEmpty());
        assertThat(manager.pendingDatabaseDrops()).isEmpty();
        assertThat(TenantEngineDirectory.engines()).isEmpty();

        // With no tenant at all there is still a sign-in page (not an error), and it does not give that away.
        CookieManager cookies = new CookieManager();
        HttpClient browser = browser(cookies);
        HttpResponse<String> home = send(browser, get("/"));
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("Sign in", "name=\"password\"").doesNotContain("no active tenant");
        assertThat(send(HttpClient.newHttpClient(), get("/fluxnova/app/tasklist/tenant-a/")).statusCode()).isEqualTo(503);

        String csrf = csrfOf(home.body());
        assertThat(landingLogin(browser, "admin", "wrong-password", csrf).headers().firstValue("Location").orElse("")).contains("error");
        assertThat(landingLogin(browser, "bob", "Bob#Passw0rd!2026", csrf).headers().firstValue("Location").orElse("")).contains("error");

        // The platform administrator (the configured credentials) signs in and is told there is no active tenant.
        HttpResponse<String> signedIn = landingLogin(browser, "admin", ADMIN_PASSWORD, csrf);
        assertThat(signedIn.statusCode()).isEqualTo(303);
        assertThat(signedIn.headers().firstValue("Location")).contains("/");
        HttpResponse<String> console = send(browser, get("/"));
        assertThat(console.statusCode()).isEqualTo(200);
        assertThat(console.body()).contains("Signed in as", "<strong>admin</strong>", "There is no active tenant").doesNotContain("<table");

        String logoutCsrf = csrfOf(console.body());
        assertThat(send(browser, post("/landing/logout", "csrf=" + URLEncoder.encode(logoutCsrf, StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded")).statusCode()).isEqualTo(303);
        assertThat(send(browser, get("/")).body()).contains("Sign in").doesNotContain("There is no active tenant");

        // Put the tenants back (new, empty databases) for the remaining test.
        writeConfig("limited", "tenant-a", "tenant-b", "tenant-c");
        await().atMost(LONG).until(() -> manager.activeTenants().size() == 3);
    }

    @Test
    @Order(18)
    void everyTenantGetsItsOwnBpmnFolder_andAFileInItIsDeployedAndStartedInThatTenantOnly() throws Exception {
        List<TenantEngine> tenants = manager.activeTenants();
        assertThat(tenants).extracting(TenantEngine::getTenantId).containsExactly("tenant-a", "tenant-b", "tenant-c");
        TenantEngine a = tenants.get(0);
        TenantEngine b = tenants.get(1);
        TenantEngine c = tenants.get(2);
        Duration wait = Duration.ofSeconds(60);

        // 0. The application creates the folder of every running tenant (on the host it is a sub-folder of the volume).
        await().atMost(wait).untilAsserted(() -> tenants.forEach(t ->
                assertThat(BPMN_DIR.resolve(t.getTenantId())).as("folder of " + t.getTenantId()).isDirectory()));
        assertThat(BPMN_DIR.resolve("tenant-d")).as("tenant-d was offboarded earlier, its folder is not deleted").isDirectory();
        await().atMost(wait).untilAsserted(() -> mvc.perform(MockMvcRequestBuilders.get("/api/tenants")).andExpect(status().isOk())
                .andExpect(jsonPath("$.bpmnDeployment.enabled").value(true))
                .andExpect(jsonPath("$.bpmnDeployment.folderFound").value(true))
                .andExpect(jsonPath("$.bpmnDeployment.tenantFolders[?(@.tenant=='tenant-a')]").isNotEmpty())
                .andExpect(jsonPath("$.bpmnDeployment.tenantFolders[?(@.tenant=='tenant-b')]").isNotEmpty())
                .andExpect(jsonPath("$.bpmnDeployment.tenantFolders[?(@.tenant=='tenant-c')]").isNotEmpty()));

        // 1. A file directly in the root belongs to no tenant: it is not deployed anywhere and nothing is started.
        Path inTheRoot = BPMN_DIR.resolve("in-the-root.bpmn");
        Files.writeString(inTheRoot, folderProcess("inTheRoot", "Nobody"));
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> tenants.forEach(t ->
                assertThat(definitions(t, "inTheRoot")).as(t.getTenantId()).isZero()));
        Files.delete(inTheRoot);

        // 2. A file in tenant-a's folder is deployed to tenant-a and started - and no other tenant sees it.
        Path folderDemo = BPMN_DIR.resolve("tenant-a").resolve("folder-demo.bpmn");
        Files.writeString(folderDemo, folderProcess("folderDemo", "Task v1"));
        await().atMost(wait).untilAsserted(() -> assertThat(instances(a, "folderDemo")).isEqualTo(1));
        assertThat(a.getEngine().getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("folderDemo")
                .singleResult().getVersion()).isEqualTo(1);
        assertThat(a.getEngine().getTaskService().createTaskQuery().processDefinitionKey("folderDemo").singleResult().getName())
                .isEqualTo("Task v1");            // the workflow really runs: it is waiting in its first task
        assertThat(a.getEngine().getRepositoryService().createDeploymentQuery().deploymentName("tenant-a/folder-demo.bpmn")
                .singleResult().getSource()).isEqualTo("bpmn-deployment-folder");
        for (TenantEngine other : List.of(b, c)) {
            assertThat(definitions(other, "folderDemo")).as(other.getTenantId()).isZero();
            assertThat(instances(other, "folderDemo")).as(other.getTenantId()).isZero();
        }

        // The management endpoint tells what happened to the file.
        mvc.perform(MockMvcRequestBuilders.get("/api/tenants")).andExpect(status().isOk())
                .andExpect(jsonPath("$.bpmnDeployment.recent[?(@.tenantId=='tenant-a' && @.file=='tenant-a/folder-demo.bpmn' "
                        + "&& @.outcome=='DEPLOYED_AND_STARTED')]").isNotEmpty())
                .andExpect(jsonPath("$.bpmnDeployment.problem").doesNotExist());

        // 3. Saving the file again without a change runs nothing a second time.
        Files.setLastModifiedTime(folderDemo, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(instances(a, "folderDemo")).isEqualTo(1);
            assertThat(a.getEngine().getRepositoryService().createDeploymentQuery().deploymentName("tenant-a/folder-demo.bpmn").count())
                    .isEqualTo(1);
        });

        // 4. A changed file is a new version of the process, and runs - in tenant-a only.
        Files.writeString(folderDemo, folderProcess("folderDemo", "Task v2"));
        await().atMost(wait).untilAsserted(() -> assertThat(instances(a, "folderDemo")).isEqualTo(2));
        assertThat(a.getEngine().getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("folderDemo")
                .latestVersion().singleResult().getVersion()).isEqualTo(2);
        assertThat(a.getEngine().getTaskService().createTaskQuery().taskName("Task v2").count()).isEqualTo(1);
        for (TenantEngine other : List.of(b, c)) {
            assertThat(definitions(other, "folderDemo")).as(other.getTenantId()).isZero();
        }

        // 5. The same file name in another tenant's folder is that tenant's own file: nothing is shared.
        Path folderDemoB = BPMN_DIR.resolve("tenant-b").resolve("folder-demo.bpmn");
        Files.writeString(folderDemoB, folderProcess("folderDemo", "Task of B"));
        await().atMost(wait).untilAsserted(() -> assertThat(instances(b, "folderDemo")).isEqualTo(1));
        assertThat(b.getEngine().getTaskService().createTaskQuery().processDefinitionKey("folderDemo").singleResult().getName())
                .isEqualTo("Task of B");
        assertThat(instances(a, "folderDemo")).isEqualTo(2);
        assertThat(a.getEngine().getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("folderDemo")
                .latestVersion().singleResult().getVersion()).isEqualTo(2);
        assertThat(instances(c, "folderDemo")).isZero();

        // 6. A process that starts by itself (timer start event) is deployed, not started by hand as well.
        Files.writeString(BPMN_DIR.resolve("tenant-c").resolve("timer-start.bpmn"), TIMER_START_PROCESS);
        await().atMost(wait).untilAsserted(() -> assertThat(c.getEngine().getManagementService()
                .createJobQuery().processDefinitionKey("timerStart").count()).isEqualTo(1));
        assertThat(instances(c, "timerStart")).isZero();
        assertThat(definitions(a, "timerStart") + definitions(b, "timerStart")).isZero();

        // 7. A file that is not a valid model is reported and does not disturb anything else.
        Path broken = BPMN_DIR.resolve("tenant-a").resolve("broken.bpmn");
        Files.writeString(broken, "this is not a BPMN model");
        await().atMost(wait).untilAsserted(() -> mvc.perform(MockMvcRequestBuilders.get("/api/tenants"))
                .andExpect(jsonPath("$.bpmnDeployment.recent[?(@.file=='tenant-a/broken.bpmn' && @.outcome=='FAILED')]").isNotEmpty()));
        assertThat(a.getEngine().getRepositoryService().createDeploymentQuery().deploymentName("tenant-a/broken.bpmn").count()).isZero();
        assertThat(manager.activeTenants()).hasSize(3);
        Files.delete(broken);

        // 8. Taking a file away and putting it back, identical, is a new landing: it runs again (the model is not redeployed).
        Files.delete(folderDemo);
        Thread.sleep(12_000);                        // longer than the grace after which a missing file counts as removed
        Files.writeString(folderDemo, folderProcess("folderDemo", "Task v2"));
        await().atMost(wait).untilAsserted(() -> assertThat(instances(a, "folderDemo")).isEqualTo(3));
        assertThat(a.getEngine().getRepositoryService().createDeploymentQuery().deploymentName("tenant-a/folder-demo.bpmn").count())
                .isEqualTo(2);
    }

    @Test
    @Order(19)
    void onboardingATenantStartsNothing_filesAlreadyInItsFolderAreOnlyDeployed_alsoWhenTheTenantIsPutBack() throws Exception {
        Duration wait = Duration.ofSeconds(60);
        // Someone prepared the folder of a tenant that does not exist yet and put a model into it.
        Path folder = Files.createDirectories(BPMN_DIR.resolve("tenant-e"));
        Files.writeString(folder.resolve("prepared.bpmn"), folderProcess("prepared", "Prepared"));

        writeConfig("limited", "tenant-a", "tenant-b", "tenant-c", "tenant-e");
        await().atMost(LONG).until(() -> manager.find("tenant-e").isPresent());
        TenantEngine first = manager.find("tenant-e").orElseThrow();

        // Onboarding: the model is deployed (it can be started by REST or from the portal) but no instance is started,
        // neither now nor a while later.
        await().atMost(wait).untilAsserted(() -> assertThat(definitions(first, "prepared")).isEqualTo(1));
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(instances(first, "prepared")).isZero());
        mvc.perform(MockMvcRequestBuilders.get("/api/tenants")).andExpect(status().isOk())
                .andExpect(jsonPath("$.bpmnDeployment.recent[?(@.tenantId=='tenant-e' && @.file=='tenant-e/prepared.bpmn' "
                        + "&& @.outcome=='DEPLOYED')]").isNotEmpty());

        // A file that lands after the tenant is running is run.
        Files.writeString(folder.resolve("landed.bpmn"), folderProcess("landed", "Landed"));
        await().atMost(wait).untilAsserted(() -> assertThat(instances(first, "landed")).isEqualTo(1));
        assertThat(instances(first, "prepared")).isZero();

        // The tenant is removed: its engine and database go, its folder and the files in it stay.
        writeConfig("limited", "tenant-a", "tenant-b", "tenant-c");
        await().atMost(LONG).until(() -> manager.find("tenant-e").isEmpty());
        await().atMost(LONG).until(() -> !databases().contains("fluxnova_tenant_e"));
        assertThat(folder).isDirectory();
        assertThat(folder.resolve("prepared.bpmn")).exists();
        assertThat(folder.resolve("landed.bpmn")).exists();

        // Put back: a new engine with an empty database, the files are still there, and again nothing is started.
        writeConfig("limited", "tenant-a", "tenant-b", "tenant-c", "tenant-e");
        await().atMost(LONG).until(() -> manager.find("tenant-e").isPresent());
        TenantEngine again = manager.find("tenant-e").orElseThrow();
        assertThat(again.getEngine()).isNotSameAs(first.getEngine());
        await().atMost(wait).untilAsserted(() -> {
            assertThat(definitions(again, "prepared")).isEqualTo(1);
            assertThat(definitions(again, "landed")).isEqualTo(1);
        });
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(instances(again, "prepared")).isZero();
            assertThat(instances(again, "landed")).isZero();
        });

        // Leave the tenants as the next test expects them.
        writeConfig("limited", "tenant-a", "tenant-b", "tenant-c");
        await().atMost(LONG).until(() -> manager.find("tenant-e").isEmpty());
        await().atMost(LONG).until(() -> !databases().contains("fluxnova_tenant_e"));
        assertThat(manager.activeTenants()).extracting(TenantEngine::getTenantId).containsExactly("tenant-a", "tenant-b", "tenant-c");
    }

    @Test
    @Order(20)
    void shutdownClosesEveryEngineAndPool() {
        List<TenantEngine> running = manager.activeTenants();
        assertThat(running).hasSize(3);

        manager.shutdown();

        assertThat(manager.activeTenants()).isEmpty();
        assertThat(TenantEngineDirectory.engines()).isEmpty();      // the web portal no longer offers any engine
        assertThat(running).allSatisfy(t -> assertThat(t.getDataSource().isClosed()).isTrue());
        assertThat(ProcessEngines.getProcessEngines()).doesNotContainKeys("tenant-a", "tenant-b", "tenant-c", "tenant-d");

        // Nothing is leaked: no job executor thread and no Hikari housekeeper / connection-adder thread survives.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(threadNames())
                .noneMatch(name -> name.startsWith("fluxnova-jobs-") || name.startsWith("fluxnova-pool-")));
    }

    /** Tenants recorded in the cluster's tenant registry for this application's scope. */
    private static List<String> registeredTenants() throws SQLException {
        return queryStrings(POSTGRES.getPostgresDatabase().getConnection(),
                "SELECT tenant_id FROM fluxnova_tenant_registry WHERE scope = 'default' ORDER BY tenant_id");
    }

    /** Names of all databases that exist in the cluster right now, asked from PostgreSQL itself. */
    private static List<String> databases() throws SQLException {
        return queryStrings(POSTGRES.getPostgresDatabase().getConnection(), "SELECT datname FROM pg_database");
    }

    private static List<String> threadNames() {
        return Thread.getAllStackTraces().keySet().stream().map(Thread::getName).toList();
    }

    // ------------------------------------------------------------------------------------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create(url(path))).GET();
    }

    private HttpRequest.Builder post(String path, String body, String contentType) {
        return HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static HttpRequest.Builder basic(HttpRequest.Builder request, String user, String password) {
        return request.header("Authorization", "Basic "
                + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws IOException, InterruptedException {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A client that keeps cookies like a browser does, and does not follow redirects. */
    private static HttpClient browser(CookieManager cookies) {
        return HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Logs in the way the portal's own front end does: opening an app page issues the CSRF token cookie, and the
     * login form is then posted with that token in the {@code X-XSRF-TOKEN} header.
     */
    private HttpResponse<String> portalLogin(HttpClient browser, CookieManager cookies, String tenant, String user,
                                             String password, String app) {
        try {
            send(browser, get("/fluxnova/app/welcome/" + tenant + "/"));
            String token = cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst()
                    .orElseThrow(() -> new AssertionError("the portal did not issue an XSRF-TOKEN cookie"));
            String form = "username=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            return send(browser, post("/fluxnova/api/admin/auth/user/" + tenant + "/login/" + app, form,
                    "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", token));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> landingLogin(HttpClient browser, String user, String password, String csrf) throws Exception {
        String form = "username=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        return send(browser, post("/landing/login", form, "application/x-www-form-urlencoded"));
    }

    private static String csrfOf(String html) {
        Matcher matcher = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).as("the page contains a form token").isTrue();
        return matcher.group(1);
    }

    private static void writeConfig(String adminUser, String... tenantIds) throws IOException {
        StringBuilder yaml = new StringBuilder()
                .append("fluxnova:\n  cluster:\n    host: localhost\n    port: ").append(POSTGRES.getPort())
                .append("\n    admin-user: ").append(adminUser)
                .append("\n    admin-password: not-checked-by-trust-auth\n  tenants:\n");
        for (String id : tenantIds) {
            yaml.append("    - id: \"").append(id).append("\"\n      dbName: \"fluxnova_")
                    .append(id.replace('-', '_')).append("\"\n");
        }
        writeRaw(yaml.toString());
    }

    /** Atomic replace, like `mv new tenants.yml` or an editor's save-via-rename, so the watcher never sees half a file. */
    private static void writeRaw(String content) throws IOException {
        Path tmp = CONFIG_FILE.resolveSibling("tenants.yml.tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static long definitions(TenantEngine tenant, String processKey) {
        return tenant.getEngine().getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(processKey).count();
    }

    private static long instances(TenantEngine tenant, String processKey) {
        return tenant.getEngine().getRuntimeService().createProcessInstanceQuery().processDefinitionKey(processKey).count();
    }

    /** A process with a plain start event that waits in one user task. */
    private static String folderProcess(String key, String taskName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="http://example.com/bpmn">
                  <process id="%s" isExecutable="true" camunda:historyTimeToLive="30">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="task"/>
                    <userTask id="task" name="%s"/>
                    <sequenceFlow id="f2" sourceRef="task" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, taskName);
    }

    private static final String TIMER_START_PROCESS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="http://example.com/bpmn">
              <process id="timerStart" isExecutable="true" camunda:historyTimeToLive="30">
                <startEvent id="start">
                  <timerEventDefinition><timeCycle>R/PT1H</timeCycle></timerEventDefinition>
                </startEvent>
                <sequenceFlow id="f1" sourceRef="start" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    private static void deploy(ProcessEngine engine, String resourceName, String bpmnXml) {
        engine.getRepositoryService().createDeployment().addString(resourceName, bpmnXml).deploy();
    }

    private static List<String> queryStrings(Connection connection, String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection c = connection; Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }

    private static final String APPROVAL_PROCESS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="http://example.com/bpmn">
              <process id="approval" isExecutable="true" camunda:historyTimeToLive="30">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                <userTask id="review" name="Review"/>
                <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    private static final String TIMER_PROCESS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="http://example.com/bpmn">
              <process id="timerDemo" isExecutable="true" camunda:historyTimeToLive="30">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="wait"/>
                <intermediateCatchEvent id="wait">
                  <timerEventDefinition><timeDuration>PT1S</timeDuration></timerEventDefinition>
                </intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="wait" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;
}
