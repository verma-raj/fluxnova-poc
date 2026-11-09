package com.example.fluxnova.web;

import com.example.fluxnova.web.LandingPage.Model;
import com.example.fluxnova.web.LandingPage.Notice;
import com.example.fluxnova.web.LandingPage.TenantRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LandingPageTest {

    private static final List<TenantRow> TENANTS = List.of(
            new TenantRow("tenant-a", "fluxnova_tenant_a", Instant.parse("2026-11-05T11:35:58Z")),
            new TenantRow("tenant-b", "fluxnova_tenant_b", Instant.parse("2026-11-05T11:36:02Z")));

    private static Model model(boolean noTenants, boolean signedIn, boolean loginFailed, List<TenantRow> tenants, List<Notice> notices) {
        return new Model(noTenants, signedIn, "admin", "token-123", loginFailed, 0, true, "/fluxnova", tenants, notices);
    }

    @Test
    void signedOutVisitorsOnlySeeASignInFormAndNoTenantNames() {
        // Even if the model (wrongly) contains tenants, they are not rendered for somebody who is not signed in.
        String html = LandingPage.render(model(false, false, false, TENANTS, List.of()));

        assertThat(html).contains("Fluxnova admin console", "Sign in", "name=\"username\"", "name=\"password\"",
                "action=\"/landing/login\"", "value=\"token-123\"");
        assertThat(html).doesNotContain("tenant-a").doesNotContain("tenant-b").doesNotContain("fluxnova_tenant");
        assertThat(html).doesNotContain("Sign-in failed");
    }

    @Test
    void theSignInPageIsTheSameWhetherOrNotAnyTenantExists() {
        // A visitor who is not signed in must not be able to tell if tenants exist.
        String withTenants = LandingPage.render(model(false, false, false, List.of(), List.of()));
        String withoutTenants = LandingPage.render(model(true, false, false, List.of(), List.of()));

        assertThat(withoutTenants).isEqualTo(withTenants).contains("name=\"password\"").doesNotContain("no active tenant");
    }

    @Test
    void aRefusedSignInIsExplainedWithoutSayingWhyItFailed() {
        String html = LandingPage.render(model(false, false, true, List.of(), List.of()));

        assertThat(html).contains("Sign-in failed", "Only administrators can sign in");
    }

    @Test
    void afterTooManyFailedAttemptsTheUserIsToldHowLongToWait() {
        String html = LandingPage.render(new Model(false, false, null, "t", false, 45, true, "/fluxnova", List.of(), List.of()));

        assertThat(html).contains("Too many failed attempts", "45 seconds").doesNotContain("Sign-in failed");
    }

    @Test
    void whenNobodyCanSignInThePageExplainsWhatToConfigure() {
        String html = LandingPage.render(new Model(true, false, null, "t", false, 0, false, "/fluxnova", List.of(), List.of()));

        assertThat(html).contains("Sign-in is not available", "FLUXNOVA_WEB_ADMIN_PASSWORD").doesNotContain("name=\"password\"");
    }

    @Test
    void signedInAdministratorsGetEveryTenantWithLinksToItsApps() {
        String html = LandingPage.render(model(false, true, false, TENANTS, List.of()));

        assertThat(html).contains("Signed in as", "<strong>admin</strong>", "action=\"/landing/logout\"", "Tenants <span class=\"count\">2</span>");
        for (String tenant : List.of("tenant-a", "tenant-b")) {
            assertThat(html).contains(
                    "href=\"/fluxnova/app/monitoring/" + tenant + "/\"",
                    "href=\"/fluxnova/app/tasklist/" + tenant + "/\"",
                    "href=\"/fluxnova/app/admin/" + tenant + "/\"");
        }
        assertThat(html).contains("fluxnova_tenant_a", "2026-11-05 11:35:58 UTC").doesNotContain("name=\"password\"")
                .doesNotContain("no active tenant");
    }

    @Test
    void aSignedInAdministratorIsToldWhenThereIsNoActiveTenant() {
        List<Notice> notices = List.of(new Notice("error", "The database 'db_x' of the removed tenant 'x' could not be dropped yet"));

        String html = LandingPage.render(model(true, true, false, List.of(), notices));

        assertThat(html).contains("Signed in as", "There is no active tenant", "tenants.yml", "action=\"/landing/logout\"",
                "could not be dropped yet");
        assertThat(html).doesNotContain("<table").doesNotContain("name=\"password\"");
    }

    @Test
    void operationsNoticesAreShownToSignedInAdministratorsOnly() {
        List<Notice> notices = List.of(new Notice("warn", "Tenant 'tenant-c' will be offboarded"),
                new Notice("error", "Tenant 'tenant-d' is configured but not running"));

        String signedIn = LandingPage.render(model(false, true, false, TENANTS, notices));
        String signedOut = LandingPage.render(model(false, false, false, TENANTS, notices));

        assertThat(signedIn).contains("notice warn", "will be offboarded", "notice error", "not running");
        assertThat(signedOut).doesNotContain("will be offboarded").doesNotContain("not running");
    }

    @Test
    void everythingThatComesFromConfigurationOrEnginesIsEscaped() {
        List<TenantRow> hostile = List.of(new TenantRow("<script>alert(1)</script>", "db\"><img src=x>", Instant.EPOCH));
        List<Notice> notices = List.of(new Notice("error", "<b>boom</b> & more"));

        String html = LandingPage.render(new Model(false, true, "<i>me</i>", "tok\"en", false, 0, true, "/fluxnova", hostile, notices));

        assertThat(html).doesNotContain("<script>alert(1)").doesNotContain("<img src=x>").doesNotContain("<b>boom</b>")
                .doesNotContain("<i>me</i>").doesNotContain("value=\"tok\"en\"");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;", "&lt;b&gt;boom&lt;/b&gt; &amp; more", "&lt;i&gt;me&lt;/i&gt;");
    }
}
