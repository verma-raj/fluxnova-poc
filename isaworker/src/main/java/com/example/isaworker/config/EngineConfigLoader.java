package com.example.isaworker.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the content of {@code fluxnova-engine.yml} into the effective worker settings, with the same rules Spring Boot
 * applies to its own configuration files: relaxed names ({@code baseUrl} or {@code base-url}), {@code ${ENV_VARIABLE}}
 * placeholders, durations such as {@code 30s}.
 * <p>
 * <b>Effective settings = baseline + file.</b> The baseline is what the application started with: {@code application.yml},
 * environment variables and the blank template packaged in the jar. Whatever the file sets (the tenant list, and optionally
 * credentials or tuning values) replaces the baseline value; whatever it leaves out keeps the baseline value. A file that is
 * empty, or has no tenants, therefore means "no tenants".
 */
@Component
@RequiredArgsConstructor
public class EngineConfigLoader {

    private static final String PREFIX = "fluxnova-workers";

    private final TenantWorkerProperties baseline;
    private final Environment environment;

    /** The settings without any external file: what the jar and the environment say (no tenant by default). */
    public TenantWorkerProperties baseline() {
        TenantWorkerProperties copy = new TenantWorkerProperties();
        BeanUtils.copyProperties(baseline, copy);
        // The binder empties and refills a list it binds into: give it its own list so the baseline is never changed.
        copy.setTenants(new ArrayList<>(baseline.getTenants() == null ? List.of() : baseline.getTenants()));
        return copy;
    }

    /**
     * @param content the bytes of the file
     * @return the effective settings
     * @throws EngineConfigException if the content is not valid YAML or a value cannot be bound; the message names the
     *                               problem (and the line, for a syntax error)
     */
    public TenantWorkerProperties parse(byte[] content) {
        try {
            List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                    .load("fluxnova-engine.yml", new ByteArrayResource(content, "fluxnova-engine.yml"));
            TenantWorkerProperties effective = baseline();
            if (!documents.isEmpty()) {                  // an empty file or one with only comments has no documents
                MutablePropertySources sources = new MutablePropertySources();
                documents.forEach(document -> sources.addLast(withoutEmptySection(document)));
                Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                        new PropertySourcesPlaceholdersResolver(environment));
                binder.bind(PREFIX, Bindable.ofInstance(effective));
            }
            return effective;
        } catch (IOException | RuntimeException e) {
            throw new EngineConfigException(describe(e), e);
        }
    }

    /**
     * {@code fluxnova-workers:} with nothing under it (or {@code fluxnova-workers: ""}) is read as the empty text "" for the
     * whole section, which cannot be turned into settings. It simply means "nothing set": drop it, so the file is treated
     * like one without the section (and so without tenants), not as an invalid file.
     */
    private static PropertySource<?> withoutEmptySection(PropertySource<?> document) {
        Object value = document.getProperty(PREFIX);
        if (value instanceof OriginTrackedValue tracked) {
            value = tracked.getValue();
        }
        if (!"".equals(value) || !(document instanceof MapPropertySource map)) {
            return document;
        }
        Map<String, Object> remaining = new LinkedHashMap<>(map.getSource());
        remaining.remove(PREFIX);
        return new OriginTrackedMapPropertySource(document.getName(), remaining, true);
    }

    /** One line: SnakeYAML's message names the problem and the line / column, but spreads it over several lines. */
    private static String describe(Throwable e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 400 ? message.substring(0, 400) + "..." : message;
    }
}
