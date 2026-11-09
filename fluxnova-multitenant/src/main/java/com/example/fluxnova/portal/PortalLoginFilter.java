package com.example.fluxnova.portal;

import com.example.fluxnova.config.RuntimeProperties;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.engine.ProcessEngine;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the web portal one login for all tenants, and for administrators only.
 * <p>
 * It wraps the portal's own login endpoint ({@code POST .../api/admin/auth/user/<engine>/login/<app>}):
 * <ol>
 *   <li><b>Only administrators.</b> Before the portal checks the password, the user must be a member of the
 *       {@code fluxnova-admin} group of the engine that is being logged in to. Anybody else gets 403, whether or not the
 *       user exists, so the answer reveals nothing. Non-administrators (for example tenants' service accounts) can still
 *       use the REST API within the permissions they were given; they just have no portal.</li>
 *   <li><b>One sign-in for every tenant.</b> After a successful login, the same session is signed in to every other
 *       engine where the same user is an administrator and the same password is valid. The administrator then switches
 *       between tenants in the engine selector without logging in again. Switch off with
 *       {@code fluxnova.runtime.web.single-sign-on=false} to require one login per engine.</li>
 * </ol>
 * The password check itself is always done by the engine; this filter never accepts a login on its own.
 * It is registered ahead of the portal's filters, so that it sees the finished login and the session it created.
 */
@Slf4j
public class PortalLoginFilter implements Filter {

    private static final Pattern LOGIN_PATH = Pattern.compile(".*/api/admin/auth/user/([^/]+)/login/[^/]+$");

    private final RuntimeProperties.Web web;

    public PortalLoginFilter(RuntimeProperties.Web web) {
        this.web = web;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        Matcher login = "POST".equalsIgnoreCase(request.getMethod()) ? LOGIN_PATH.matcher(request.getRequestURI()) : null;
        if (login == null || !login.matches()) {
            chain.doFilter(request, response);
            return;
        }

        String engineName = URLDecoder.decode(login.group(1), StandardCharsets.UTF_8);
        String username = request.getParameter("username");
        String password = request.getParameter("password");
        ProcessEngine engine = TenantEngineDirectory.engines().get(engineName);

        if (engine != null && !PortalSessions.isAdministrator(engine, username)) {
            log.warn("Portal login to engine '{}' refused: '{}' is not an administrator", engineName, printable(username));
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"type\":\"PortalLoginRestricted\",\"message\":\"Only administrators can log in to this portal.\"}");
            return;
        }

        chain.doFilter(request, response);

        if (web.isSingleSignOn() && engine != null && response.getStatus() == HttpServletResponse.SC_OK) {
            // The portal has signed the user in to this engine; extend that to every other engine the user administers.
            PortalSessions.signIn(request.getSession(false), username, password);
        }
    }

    /** Request data in a log line: no line breaks (log injection) and a sane length. */
    private static String printable(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("[\\r\\n\\t]", "_");
        return clean.length() > 64 ? clean.substring(0, 64) + "..." : clean;
    }
}
