package com.example.fluxnova.web;

import com.example.fluxnova.config.RuntimeProperties;
import com.example.fluxnova.engine.FluxnovaEngineManager;
import com.example.fluxnova.engine.TenantEngine;
import com.example.fluxnova.portal.PortalSessions;
import com.example.fluxnova.watch.TenantConfigWatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.spring.boot.starter.property.FluxnovaBpmProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The neutral landing page at {@code /}: a single place where an administrator or the operations team signs in once and
 * then chooses which tenant to open. Nothing is preselected; no tenant's login is shown first.
 * <ul>
 *   <li>Before signing in, the page shows only a sign-in form, whether or not any tenant exists. Tenant names are not
 *       disclosed to visitors who are not signed in.</li>
 *   <li>Only administrators can sign in. Two kinds of credentials are accepted: a tenant engine's own administrator
 *       (one sign-in then covers every engine where the same administrator exists, see {@link PortalSessions}), and the
 *       <b>platform administrator</b> from the configuration ({@code fluxnova.runtime.web.admin-user} / {@code admin-password}).
 *       The platform administrator is what makes sign-in possible while <em>no tenant exists</em>, because a tenant's
 *       administrators live inside that tenant's database. After signing in, the page says so if there is no active tenant.</li>
 *   <li>Once signed in, the page also lists what the operations team should know: tenants that are about to be
 *       offboarded, tenants that failed to start, and databases that could not be dropped yet.</li>
 * </ul>
 * The forms carry a per-session token (CSRF protection); a successful sign-in gets a new session id (session fixation);
 * repeated failures for a user name pause sign-in for that name ({@link LoginThrottle}).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class LandingController {

    private static final String CSRF_ATTRIBUTE = "fluxnova.landing.csrf";
    private static final String PLATFORM_ADMIN_ATTRIBUTE = "fluxnova.landing.platformAdmin";

    private final FluxnovaEngineManager engineManager;
    private final TenantConfigWatcher watcher;
    private final FluxnovaBpmProperties bpm;
    private final RuntimeProperties runtime;
    private final LoginThrottle throttle;
    private final SecureRandom random = new SecureRandom();

    @GetMapping("/")
    public ResponseEntity<String> landing(HttpServletRequest request, @RequestParam(name = "error", required = false) String error,
                                          @RequestParam(name = "wait", defaultValue = "0") long wait) {
        HttpSession session = request.getSession(true);
        String administrator = signedInAdministrator(session);
        List<TenantEngine> running = engineManager.activeTenants();
        boolean signedIn = administrator != null;

        LandingPage.Model model = new LandingPage.Model(
                running.isEmpty(), signedIn, administrator, csrfToken(session),
                "1".equals(error), "locked".equals(error) ? Math.max(0, Math.min(wait, 3600)) : 0,
                !running.isEmpty() || platformAdminConfigured(), bpm.getWebapp().getApplicationPath(),
                signedIn ? running.stream().map(t -> new LandingPage.TenantRow(t.getTenantId(), t.getDbName(), t.getOnboardedAt())).toList() : List.of(),
                signedIn ? notices() : List.of());

        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
                .body(LandingPage.render(model));
    }

    @PostMapping("/landing/login")
    public ResponseEntity<Void> login(HttpServletRequest request, @RequestParam(name = "username", defaultValue = "") String username,
                                      @RequestParam(name = "password", defaultValue = "") String password,
                                      @RequestParam(name = "csrf", defaultValue = "") String csrf) {
        HttpSession session = request.getSession(false);
        if (session == null || !validCsrf(session, csrf)) {
            log.warn("Landing page sign-in refused: missing or wrong form token");
            return redirect("/?error=1");
        }
        long wait = throttle.secondsToWait(username);
        if (wait > 0) {
            log.warn("Landing page sign-in for '{}' paused for {} s after repeated failures", printable(username), wait);
            return redirect("/?error=locked&wait=" + wait);
        }

        int engines = PortalSessions.signIn(session, username, password);          // a tenant engine's administrator
        boolean platform = platformAdminMatches(username, password);               // the configured platform administrator
        if (engines == 0 && !platform) {
            throttle.failure(username);
            log.warn("Landing page sign-in refused for '{}': wrong password, or not an administrator", printable(username));
            return redirect("/?error=1");
        }
        throttle.success(username);
        if (platform) {
            session.setAttribute(PLATFORM_ADMIN_ATTRIBUTE, username);
        }
        request.changeSessionId();      // a new session id after authentication, against session fixation
        log.info("Landing page: '{}' signed in ({} tenant engine(s){})", printable(username), engines, platform ? ", platform administrator" : "");
        return redirect("/");
    }

    @PostMapping("/landing/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, @RequestParam(name = "csrf", defaultValue = "") String csrf) {
        HttpSession session = request.getSession(false);
        if (session != null && validCsrf(session, csrf)) {
            session.invalidate();       // drops the sign-in to every tenant engine at once
        }
        return redirect("/");
    }

    /** The administrator this session is signed in as (a tenant engine's administrator, or the platform administrator), or null. */
    private String signedInAdministrator(HttpSession session) {
        String engineAdministrator = PortalSessions.administratorOf(session);
        if (engineAdministrator != null) {
            return engineAdministrator;
        }
        Object platform = session.getAttribute(PLATFORM_ADMIN_ATTRIBUTE);
        // Only while the platform administrator is still configured as that user.
        return platform instanceof String user && platformAdminConfigured() && user.equals(runtime.getWeb().getAdminUser()) ? user : null;
    }

    private boolean platformAdminConfigured() {
        String password = runtime.getWeb().getAdminPassword();
        String user = runtime.getWeb().getAdminUser();
        return password != null && !password.isBlank() && user != null && !user.isBlank();
    }

    /** Constant-time comparison; both parts are always compared so the answer does not reveal which one was wrong. */
    private boolean platformAdminMatches(String username, String password) {
        if (!platformAdminConfigured()) {
            return false;
        }
        boolean user = MessageDigest.isEqual(username.getBytes(StandardCharsets.UTF_8), runtime.getWeb().getAdminUser().getBytes(StandardCharsets.UTF_8));
        boolean pass = MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), runtime.getWeb().getAdminPassword().getBytes(StandardCharsets.UTF_8));
        return user & pass;
    }

    /** What the operations team should see next to the tenant list. */
    private List<LandingPage.Notice> notices() {
        List<LandingPage.Notice> notices = new ArrayList<>();
        watcher.scheduledOffboardings().forEach(s -> notices.add(new LandingPage.Notice("warn",
                "Tenant '" + s.tenantId() + "' was removed from tenants.yml. At " + s.offboardAt() + " its engine is stopped and its database '"
                        + s.dbName() + "' is dropped, unless the tenant is added back before then.")));
        engineManager.failedTenants().forEach(f -> notices.add(new LandingPage.Notice("error",
                "Tenant '" + f.tenantId() + "' is configured but not running (attempt " + f.attempts() + ", next retry " + f.nextRetryAt()
                        + "): " + f.lastError())));
        engineManager.pendingDatabaseDrops().forEach(p -> notices.add(new LandingPage.Notice("error",
                "The database '" + p.dbName() + "' of the removed tenant '" + p.tenantId() + "' could not be dropped yet (attempt "
                        + p.attempts() + ", next try " + p.nextRetryAt() + "): " + p.lastError())));
        return notices;
    }

    private String csrfToken(HttpSession session) {
        Object existing = session.getAttribute(CSRF_ATTRIBUTE);
        if (existing instanceof String token) {
            return token;
        }
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(CSRF_ATTRIBUTE, token);
        return token;
    }

    private static boolean validCsrf(HttpSession session, String submitted) {
        Object expected = session.getAttribute(CSRF_ATTRIBUTE);
        return expected instanceof String token && !submitted.isEmpty()
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), submitted.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location)).build();
    }

    /** Request data in a log line: no line breaks (log injection) and a sane length. */
    private static String printable(String value) {
        String clean = value.replaceAll("[\\r\\n\\t]", "_");
        return clean.length() > 64 ? clean.substring(0, 64) + "..." : clean;
    }
}
