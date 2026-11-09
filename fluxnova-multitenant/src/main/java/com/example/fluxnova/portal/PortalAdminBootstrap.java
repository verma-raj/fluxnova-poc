package com.example.fluxnova.portal;

import com.example.fluxnova.config.RuntimeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.finos.fluxnova.bpm.engine.authorization.Groups;
import org.finos.fluxnova.bpm.engine.identity.Group;
import org.finos.fluxnova.bpm.engine.identity.User;
import org.springframework.stereotype.Component;

/**
 * Makes sure every tenant engine has an administrator who can sign in to the web portal.
 * <p>
 * The portal authenticates against the user tables of the engine that is selected in the URL
 * ({@code /app/cockpit/tenant-a/}), and every tenant has its own database, so every tenant needs its own admin user.
 * This runs once per onboarded engine and is idempotent: anything that already exists is left exactly as it is, so a
 * password that was changed in the Admin app is never overwritten on the next restart.
 * <p>
 * The administrator's <em>permissions</em> come from {@code AdministratorAuthorizationPlugin}, which the engine manager
 * installs: it grants the {@code fluxnova-admin} group full access to every resource, including the portal apps.
 * This class only creates the user, the group and the membership.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalAdminBootstrap {

    private final RuntimeProperties runtime;

    /**
     * @throws RuntimeException if the user cannot be created (e.g. the password violates the engine's password policy)
     */
    public void ensureAdministrator(String tenantId, ProcessEngine engine) {
        RuntimeProperties.Web web = runtime.getWeb();
        String userId = web.getAdminUser();
        String password = web.getAdminPassword();

        if (password == null || password.isBlank()) {
            log.warn("Tenant '{}': no portal administrator created because fluxnova.runtime.web.admin-password is not set. "
                    + "Set FLUXNOVA_WEB_ADMIN_PASSWORD, or create the first user on /app/admin/{}/setup/", tenantId, tenantId);
            return;
        }

        IdentityService identity = engine.getIdentityService();
        if (identity.isReadOnly()) {
            log.warn("Tenant '{}': identity service is read-only, portal administrator not created", tenantId);
            return;
        }

        if (identity.createUserQuery().userId(userId).count() == 0) {
            User user = identity.newUser(userId);
            user.setFirstName(web.getAdminFirstName());
            user.setLastName(web.getAdminLastName());
            user.setEmail(web.getAdminEmail());
            user.setPassword(password);
            identity.saveUser(user);
            log.info("Tenant '{}': created portal administrator '{}'", tenantId, userId);
        }

        if (identity.createGroupQuery().groupId(Groups.CAMUNDA_ADMIN).count() == 0) {
            Group group = identity.newGroup(Groups.CAMUNDA_ADMIN);
            group.setName("Fluxnova Administrators");
            group.setType(Groups.GROUP_TYPE_SYSTEM);
            identity.saveGroup(group);
        }

        if (identity.createUserQuery().userId(userId).memberOfGroup(Groups.CAMUNDA_ADMIN).count() == 0) {
            identity.createMembership(userId, Groups.CAMUNDA_ADMIN);
            log.info("Tenant '{}': '{}' is a member of '{}'", tenantId, userId, Groups.CAMUNDA_ADMIN);
        }
    }
}
