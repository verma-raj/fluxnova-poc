package com.example.fluxnova.portal;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Sends portal addresses that name no tenant to the neutral landing page.
 * <p>
 * The portal's own behaviour for {@code /fluxnova/}, {@code /fluxnova/app/} or {@code /fluxnova/app/tasklist/} is to pick
 * an engine for the visitor and redirect there, which in a multi-tenant installation drops the visitor into one arbitrary
 * tenant's login. Those addresses go to {@code /} instead, where the administrator signs in once and chooses a tenant.
 * Addresses that do name a tenant ({@code /fluxnova/app/tasklist/tenant-b/}) are not affected.
 */
public class EnginelessPortalFilter implements Filter {

    private final Pattern enginelessAddress;

    /** @param applicationPath where the portal is mounted, normally {@code /fluxnova} */
    public EnginelessPortalFilter(String applicationPath) {
        this.enginelessAddress = Pattern.compile("^" + Pattern.quote(applicationPath) + "(/app(/(welcome|tasklist|monitoring|admin))?)?/?$");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        String method = request.getMethod();
        boolean readOnly = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        String path = request.getRequestURI().substring(request.getContextPath().length());

        if (readOnly && enginelessAddress.matcher(path).matches()) {
            ((HttpServletResponse) servletResponse).sendRedirect(request.getContextPath() + "/");
            return;
        }
        chain.doFilter(servletRequest, servletResponse);
    }
}
