package com.example.fluxnova.portal;

import org.finos.fluxnova.bpm.engine.rest.security.auth.ProcessEngineAuthenticationFilter;
import org.finos.fluxnova.bpm.engine.rest.security.auth.impl.HttpBasicAuthenticationProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts HTTP Basic authentication in front of the public REST API ({@code /engine-rest/*}).
 * <p>
 * The REST API is open by default and can deploy, start and delete processes, so without this anybody who can reach
 * the port has full control of every tenant. The filter reads the engine name from the URL
 * ({@code /engine-rest/engine/tenant-a/...}) and checks the Basic credentials against the users of <em>that</em>
 * engine; the engine's authorizations then decide what the user may do. A tenant's user therefore cannot call another
 * tenant's engine, unless the same user id and password exist there too.
 */
@Configuration
public class RestSecurityConfiguration {

    static final String REST_PATH = "/engine-rest";

    @Bean
    public FilterRegistrationBean<ProcessEngineAuthenticationFilter> engineRestAuthenticationFilter() {
        FilterRegistrationBean<ProcessEngineAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setName("engine-rest-authentication");
        registration.setFilter(new ProcessEngineAuthenticationFilter());
        registration.addInitParameter(ProcessEngineAuthenticationFilter.AUTHENTICATION_PROVIDER_PARAM,
                HttpBasicAuthenticationProvider.class.getName());
        // The filter needs to know where the REST servlet is mounted to find the engine name in the URL.
        registration.addInitParameter(ProcessEngineAuthenticationFilter.SERVLET_PATH_PREFIX, REST_PATH);
        registration.addUrlPatterns(REST_PATH + "/*");
        return registration;
    }
}
