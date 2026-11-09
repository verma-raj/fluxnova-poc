package com.example.fluxnova.portal;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Answers the portal's pages with a plain "no tenants yet" page while no tenant engine is running.
 * <p>
 * The portal cannot work without at least one engine and fails with an HTTP 500 and a stack trace otherwise, which is
 * what a freshly started container shows before the first tenant is added to {@code tenants.yml} (or after all of them
 * are offboarded). A 503 with an explanation, and a {@code Retry-After}, is the honest answer for that state.
 */
public class NoTenantsFilter implements Filter {

    private static final String PAGE = """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="utf-8"><title>No tenants</title></head>
            <body style="font-family:sans-serif;max-width:40em;margin:4em auto">
            <h1>No tenants are configured yet</h1>
            <p>There is no process engine to show. Add a tenant to <code>tenants.yml</code>; it is picked up automatically
            within a few seconds, and this page then opens the portal.</p>
            </body></html>
            """;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!TenantEngineDirectory.engines().isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletResponse http = (HttpServletResponse) response;
        http.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        http.setHeader("Retry-After", "15");
        http.setContentType("text/html");
        http.setCharacterEncoding(StandardCharsets.UTF_8.name());
        http.getWriter().write(PAGE);
    }
}
