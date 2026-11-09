package com.example.fluxnova.portal;

import com.example.fluxnova.config.RuntimeProperties;
import org.finos.fluxnova.bpm.spring.boot.starter.property.FluxnovaBpmProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers {@link PortalLoginFilter} in front of the portal's login endpoint. */
@Configuration
public class PortalLoginConfiguration {

    @Bean
    public FilterRegistrationBean<PortalLoginFilter> portalLoginFilter(RuntimeProperties runtime, FluxnovaBpmProperties bpm) {
        FilterRegistrationBean<PortalLoginFilter> registration = new FilterRegistrationBean<>(new PortalLoginFilter(runtime.getWeb()));
        registration.setName("portal-login");
        // Only the portal's authentication endpoints; the path follows fluxnova.bpm.webapp.application-path (/fluxnova).
        registration.addUrlPatterns(bpm.getWebapp().getApplicationPath() + "/api/admin/auth/user/*");
        // Ahead of the portal's own filters, so that after the chain has run the login and its session are complete.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** Portal addresses without a tenant go to the neutral landing page instead of into one tenant's login. */
    @Bean
    public FilterRegistrationBean<EnginelessPortalFilter> enginelessPortalFilter(FluxnovaBpmProperties bpm) {
        String applicationPath = bpm.getWebapp().getApplicationPath();
        FilterRegistrationBean<EnginelessPortalFilter> registration = new FilterRegistrationBean<>(new EnginelessPortalFilter(applicationPath));
        registration.setName("portal-engineless");
        registration.addUrlPatterns(applicationPath + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);   // before the no-tenants page and the portal's own filters
        return registration;
    }

    /** While no tenant engine runs, the portal's pages say so instead of failing with an HTTP 500. */
    @Bean
    public FilterRegistrationBean<NoTenantsFilter> noTenantsFilter(FluxnovaBpmProperties bpm) {
        FilterRegistrationBean<NoTenantsFilter> registration = new FilterRegistrationBean<>(new NoTenantsFilter());
        registration.setName("portal-no-tenants");
        registration.addUrlPatterns(bpm.getWebapp().getApplicationPath() + "/app/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
