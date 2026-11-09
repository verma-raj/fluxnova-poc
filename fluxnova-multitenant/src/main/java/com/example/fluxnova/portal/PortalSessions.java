package com.example.fluxnova.portal;

import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.finos.fluxnova.bpm.engine.authorization.Groups;
import org.finos.fluxnova.bpm.webapp.impl.security.auth.AuthenticationUtil;
import org.finos.fluxnova.bpm.webapp.impl.security.auth.Authentications;
import org.finos.fluxnova.bpm.webapp.impl.security.auth.UserAuthentication;

import java.util.Map;

/**
 * The portal's notion of "signed in", shared by the portal login filter and the landing page so that both apply exactly
 * the same rules:
 * <ul>
 *   <li>only <b>administrators</b> (members of the engine's {@code fluxnova-admin} group) may sign in;</li>
 *   <li>one sign-in covers <b>every tenant engine</b> where the same administrator exists with the same password;</li>
 *   <li>the engine itself always checks the password (including its brute-force delay); nothing here accepts a login on
 *       its own.</li>
 * </ul>
 * A sign-in is stored in the HTTP session in the portal's own {@code Authentications} object, which is what the portal's
 * apps read, so a sign-in made here is a sign-in in Monitoring, Tasklist and Admin.
 */
@Slf4j
public final class PortalSessions {

    private PortalSessions() {
    }

    /** Fails closed: any problem while asking the engine counts as "not an administrator". */
    public static boolean isAdministrator(ProcessEngine engine, String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        try {
            return engine.getIdentityService().createUserQuery().userId(userId).memberOfGroup(Groups.CAMUNDA_ADMIN).count() > 0;
        } catch (RuntimeException e) {
            log.debug("Could not check the administrator group of a user: {}", e.toString());
            return false;
        }
    }

    /**
     * Signs the session in to every running engine where {@code userId} is an administrator and {@code password} is
     * valid, unless the session is signed in to that engine already.
     *
     * @return how many engines the session was newly signed in to
     */
    public static int signIn(HttpSession session, String userId, String password) {
        if (session == null || userId == null || password == null) {
            return 0;
        }
        Authentications authentications = AuthenticationUtil.getAuthsFromSession(session);
        int signedIn = 0;
        for (Map.Entry<String, ProcessEngine> entry : TenantEngineDirectory.engines().entrySet()) {
            String engineName = entry.getKey();
            ProcessEngine engine = entry.getValue();
            if (authentications.hasAuthenticationForProcessEngine(engineName)) {
                continue;
            }
            try {
                if (isAdministrator(engine, userId) && engine.getIdentityService().checkPassword(userId, password)) {
                    authentications.addOrReplace(AuthenticationUtil.createAuthentication(engine, userId));
                    signedIn++;
                }
            } catch (RuntimeException e) {
                // e.g. the engine is being offboarded right now; the administrator can still sign in to it separately.
                log.debug("Sign-in to engine '{}' skipped: {}", engineName, e.toString());
            }
        }
        if (signedIn > 0) {
            AuthenticationUtil.updateSession(session, authentications);
        }
        return signedIn;
    }

    /** The user id the session is signed in as: an administrator of at least one running engine, or {@code null}. */
    public static String administratorOf(HttpSession session) {
        if (session == null) {
            return null;
        }
        for (UserAuthentication authentication : AuthenticationUtil.getAuthsFromSession(session).getAuthentications()) {
            ProcessEngine engine = TenantEngineDirectory.engines().get(authentication.getProcessEngineName());
            if (engine != null && isAdministrator(engine, authentication.getIdentityId())) {
                return authentication.getIdentityId();
            }
        }
        return null;
    }
}
