package com.example.fluxnova.web;

import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders the neutral landing page as plain HTML (no scripts, no template engine).
 * <p>
 * Every value that comes from configuration or from the engines is HTML-escaped. The tenant list and the operations
 * notices are only ever rendered for a signed-in administrator, whatever the model contains. The page before sign-in is
 * the same whether or not any tenant exists, so it does not reveal that.
 */
final class LandingPage {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /** One running tenant. */
    record TenantRow(String id, String database, Instant since) {
    }

    /** A thing the operations team should know: {@code error} (something is broken) or {@code warn} (something is about to happen). */
    record Notice(String level, String text) {
    }

    /**
     * @param noTenants      no tenant engine is running
     * @param signedIn       an administrator is signed in, so the tenant list and notices may be shown
     * @param loginFailed    the last sign-in attempt was refused
     * @param waitSeconds    greater than 0 if sign-in is paused because of too many failed attempts
     * @param signInPossible whether anybody can sign in at all (a tenant runs, or a platform administrator password is configured)
     * @param csrf           token that the sign-in / sign-out forms must send back
     * @param appPath        where the portal is mounted, normally {@code /fluxnova}
     */
    record Model(boolean noTenants, boolean signedIn, String userName, String csrf, boolean loginFailed, long waitSeconds,
                 boolean signInPossible, String appPath, List<TenantRow> tenants, List<Notice> notices) {
    }

    private LandingPage() {
    }

    static String render(Model model) {
        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Fluxnova admin console</title><style>").append(STYLE).append("</style></head><body><main>")
                .append("<header><h1>Fluxnova admin console</h1>")
                .append("<p class=\"sub\">For administrators and the operations team. One sign-in covers every tenant.</p></header>");

        if (!model.signedIn()) {
            if (model.signInPossible()) {
                signIn(html, model);
            } else {
                html.append("<section class=\"card\"><h2>Sign-in is not available</h2>")
                        .append("<p>No tenant is running and no administrator password is configured for this console. ")
                        .append("Set <code>FLUXNOVA_WEB_ADMIN_PASSWORD</code>, or add a tenant to <code>tenants.yml</code>.</p></section>");
            }
        } else {
            console(html, model);
        }
        return html.append("</main></body></html>").toString();
    }

    private static void signIn(StringBuilder html, Model model) {
        html.append("<section class=\"card narrow\"><h2>Sign in</h2>")
                .append("<p class=\"muted\">Administrators only.</p>");
        if (model.waitSeconds() > 0) {
            html.append("<p class=\"notice error\" role=\"alert\">Too many failed attempts. Try again in ")
                    .append(model.waitSeconds()).append(" seconds.</p>");
        } else if (model.loginFailed()) {
            html.append("<p class=\"notice error\" role=\"alert\">Sign-in failed. Check the user name and password. ")
                    .append("Only administrators can sign in.</p>");
        }
        html.append("<form method=\"post\" action=\"/landing/login\">")
                .append("<input type=\"hidden\" name=\"csrf\" value=\"").append(esc(model.csrf())).append("\">")
                .append("<label for=\"username\">User name</label>")
                .append("<input id=\"username\" name=\"username\" type=\"text\" autocomplete=\"username\" required autofocus>")
                .append("<label for=\"password\">Password</label>")
                .append("<input id=\"password\" name=\"password\" type=\"password\" autocomplete=\"current-password\" required>")
                .append("<button type=\"submit\">Sign in</button></form></section>");
    }

    private static void console(StringBuilder html, Model model) {
        html.append("<div class=\"bar\"><span>Signed in as <strong>").append(esc(model.userName())).append("</strong></span>")
                .append("<form method=\"post\" action=\"/landing/logout\"><input type=\"hidden\" name=\"csrf\" value=\"")
                .append(esc(model.csrf())).append("\"><button type=\"submit\" class=\"secondary\">Sign out</button></form></div>");

        for (Notice notice : model.notices()) {
            html.append("<p class=\"notice ").append("error".equals(notice.level()) ? "error" : "warn").append("\">")
                    .append(esc(notice.text())).append("</p>");
        }

        if (model.noTenants()) {
            html.append("<section class=\"card\"><h2>There is no active tenant</h2>")
                    .append("<p>No tenant engine is running at the moment. Add a tenant to <code>tenants.yml</code>: it is picked up ")
                    .append("automatically within a few seconds and then appears here, ready to open without signing in again.</p></section>");
            return;
        }

        html.append("<section class=\"card\"><h2>Tenants <span class=\"count\">").append(model.tenants().size()).append("</span></h2>")
                .append("<table><thead><tr><th>Tenant</th><th>Database</th><th>Running since</th><th>Open</th></tr></thead><tbody>");
        for (TenantRow tenant : model.tenants()) {
            String base = model.appPath() + "/app/";
            String id = esc(tenant.id());
            html.append("<tr><td><strong>").append(id).append("</strong></td>")
                    .append("<td><code>").append(esc(tenant.database())).append("</code></td>")
                    .append("<td>").append(tenant.since() == null ? "" : TIME.format(tenant.since())).append("</td><td class=\"links\">")
                    .append(link(base + "monitoring/", id, "Monitoring"))
                    .append(link(base + "tasklist/", id, "Tasklist"))
                    .append(link(base + "admin/", id, "Admin"))
                    .append("</td></tr>");
        }
        html.append("</tbody></table></section>");
    }

    private static String link(String prefix, String escapedTenantId, String label) {
        return "<a href=\"" + esc(prefix) + escapedTenantId + "/\">" + label + "</a>";
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }

    private static final String STYLE = """
            :root{color-scheme:light dark;--bg:#f4f6f9;--card:#fff;--fg:#1c2330;--muted:#5b6678;--line:#d9dee7;--accent:#1f5eff;\
            --accent-fg:#fff;--warn-bg:#fff4d6;--warn-fg:#5e4300;--err-bg:#fde8e8;--err-fg:#8a1c1c}
            @media (prefers-color-scheme:dark){:root{--bg:#10141b;--card:#181e28;--fg:#e8ecf3;--muted:#9aa6ba;--line:#2a3342;\
            --accent:#6c9bff;--accent-fg:#0b1020;--warn-bg:#3a2f12;--warn-fg:#f3d98a;--err-bg:#3d1b1b;--err-fg:#f5b5b5}}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
            main{max-width:58rem;margin:0 auto;padding:2rem 1rem 4rem}
            h1{margin:0;font-size:1.6rem}h2{margin:0 0 .75rem;font-size:1.15rem}
            .sub,.muted{color:var(--muted);margin:.25rem 0 1.25rem}
            .card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:1.25rem 1.5rem;margin:1rem 0}
            .narrow{max-width:26rem}
            label{display:block;margin:.9rem 0 .25rem;font-weight:600}
            input[type=text],input[type=password]{width:100%;padding:.55rem .65rem;border:1px solid var(--line);border-radius:6px;\
            background:var(--bg);color:var(--fg);font:inherit}
            button{margin-top:1.1rem;padding:.55rem 1.1rem;border:0;border-radius:6px;background:var(--accent);color:var(--accent-fg);\
            font:inherit;font-weight:600;cursor:pointer}
            button.secondary{margin:0;background:transparent;color:var(--accent);border:1px solid var(--accent)}
            .bar{display:flex;justify-content:space-between;align-items:center;margin:.5rem 0}
            .notice{padding:.65rem .9rem;border-radius:8px;margin:.75rem 0}
            .notice.warn{background:var(--warn-bg);color:var(--warn-fg)}.notice.error{background:var(--err-bg);color:var(--err-fg)}
            table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:.55rem .5rem;border-bottom:1px solid var(--line)}
            th{color:var(--muted);font-size:.85rem;text-transform:uppercase;letter-spacing:.04em}
            td.links a{margin-right:1rem;color:var(--accent);font-weight:600;text-decoration:none}td.links a:hover{text-decoration:underline}
            .count{color:var(--muted);font-weight:400}code{font-size:.9em}
            """;
}
