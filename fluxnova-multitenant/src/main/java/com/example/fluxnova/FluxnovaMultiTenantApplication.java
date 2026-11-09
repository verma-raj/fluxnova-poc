package com.example.fluxnova;

import com.example.fluxnova.config.RuntimeProperties;
import org.finos.fluxnova.bpm.spring.boot.starter.FluxnovaBpmAutoConfiguration;
import org.finos.fluxnova.bpm.spring.boot.starter.property.FluxnovaBpmProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * Hosts many independent Fluxnova process engines (one per tenant, one PostgreSQL database per tenant)
 * inside a single JVM. Tenants are onboarded at runtime from an external {@code tenants.yml}.
 * <p>
 * Two auto-configurations are intentionally switched off, because every tenant gets a programmatically built pool and
 * engine ({@code FluxnovaEngineManager}) instead of the one Spring-managed engine they would create:
 * <ul>
 *   <li>{@link FluxnovaBpmAutoConfiguration}, the Fluxnova starter's single-engine setup. Only the web portal and REST
 *       parts of the starters are kept; they find the tenant engines through {@code TenantProcessEngineProvider}.</li>
 *   <li>Spring Boot's {@code DataSourceAutoConfiguration}, which would otherwise try to create a default DataSource at start-up.</li>
 * </ul>
 * {@link FluxnovaBpmProperties} ({@code fluxnova.bpm.*}) is still bound because the web portal configuration reads it.
 */
@SpringBootApplication(
        exclude = FluxnovaBpmAutoConfiguration.class,
        // By name: Spring Boot 4 moved it to org.springframework.boot.jdbc.autoconfigure, and a name is also fine if that module is absent.
        excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@EnableScheduling
@EnableConfigurationProperties({RuntimeProperties.class, FluxnovaBpmProperties.class})
public class FluxnovaMultiTenantApplication {

    public static void main(String[] args) {
        useUtc();
        SpringApplication.run(FluxnovaMultiTenantApplication.class, args);
    }

    /**
     * Runs the JVM in UTC, which is what a Fargate task does anyway.
     * <p>
     * The PostgreSQL JDBC driver announces the JVM's default time zone on every connection. A developer machine can
     * report a legacy alias such as {@code Asia/Calcutta}, and current PostgreSQL images (Debian 13 tzdata) do not
     * know those aliases and refuse the connection ({@code invalid value for parameter "TimeZone"}) - then not even
     * the admin connection that creates the tenant databases can be opened. A single zone on every node also keeps
     * the engines' timestamps consistent when several tasks share the same tenant databases.
     */
    static void useUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
