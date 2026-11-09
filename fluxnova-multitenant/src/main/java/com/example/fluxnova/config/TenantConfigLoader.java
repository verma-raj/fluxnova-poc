package com.example.fluxnova.config;

import com.example.fluxnova.db.DatabaseProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses and validates the raw bytes of {@code tenants.yml}.
 * <p>
 * Parsing and validation are all-or-nothing: either a fully valid {@link TenantConfigProperties} is returned or a
 * {@link TenantConfigException} describing every problem is thrown. Callers therefore never act on a half-valid
 * file, e.g. one that an editor is still in the middle of writing.
 */
@Component
@RequiredArgsConstructor
public class TenantConfigLoader {

    private static final int MAX_DOCUMENT_BYTES = 1024 * 1024;
    private static final String DEFAULT_ADMIN_DATABASE = "postgres";

    private static final Pattern TENANT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$");
    /** Databases that exist on every PostgreSQL / RDS cluster and must never be adopted as a tenant database. */
    private static final Set<String> RESERVED_DATABASES = Set.of("postgres", "template0", "template1", "rdsadmin");

    private final RuntimeProperties runtime;

    /**
     * @param content the complete file content (UTF-8, with or without BOM - Windows editors often add one)
     * @throws TenantConfigException if the document is empty, malformed, contains unknown keys, or is invalid
     */
    public TenantConfigProperties parse(byte[] content) {
        if (content == null || content.length == 0) {
            throw new TenantConfigException("tenants.yml is empty");
        }
        if (content.length > MAX_DOCUMENT_BYTES) {
            throw new TenantConfigException("tenants.yml is larger than " + MAX_DOCUMENT_BYTES + " bytes");
        }

        TenantConfigProperties parsed;
        try {
            // load(InputStream) detects and strips a UTF-8 / UTF-16 byte order mark.
            parsed = newYaml().load(new ByteArrayInputStream(content));
        } catch (YAMLException | IllegalArgumentException e) {
            throw new TenantConfigException("tenants.yml cannot be parsed: " + describe(e), e);
        }
        if (parsed == null || parsed.getFluxnova() == null) {
            throw new TenantConfigException("tenants.yml has no 'fluxnova:' root element");
        }

        TenantConfigProperties.Fluxnova root = parsed.getFluxnova();
        applyOverridesAndDefaults(root);
        validate(root);
        return parsed;
    }

    /** A fresh Yaml per parse: Yaml instances are not thread-safe and parsing is infrequent. */
    private static Yaml newYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);      // limits "billion laughs" alias expansion
        options.setCodePointLimit(MAX_DOCUMENT_BYTES);

        // Constructing the declared root type (instead of arbitrary tags) also means the file cannot instantiate
        // any other class - there is no global-tag deserialisation.
        Constructor constructor = new Constructor(TenantConfigProperties.class, options);
        constructor.setPropertyUtils(new KebabCasePropertyUtils());
        return new Yaml(constructor);
    }

    private void applyOverridesAndDefaults(TenantConfigProperties.Fluxnova root) {
        TenantConfigProperties.Cluster cluster = root.getCluster();
        if (cluster != null) {
            String override = runtime.getAdminPassword();
            if (override != null && !override.isBlank()) {
                cluster.setAdminPassword(override);
            }
            if (isBlank(cluster.getAdminDatabase())) {
                cluster.setAdminDatabase(DEFAULT_ADMIN_DATABASE);
            }
        }
        if (root.getTenants() == null) {
            root.setTenants(new ArrayList<>());   // "tenants:" with no entries
        }
    }

    private void validate(TenantConfigProperties.Fluxnova root) {
        List<String> errors = new ArrayList<>();

        TenantConfigProperties.Cluster cluster = root.getCluster();
        if (cluster == null) {
            errors.add("fluxnova.cluster is missing");
        } else {
            if (isBlank(cluster.getHost()) || !HOST.matcher(cluster.getHost()).matches()) {
                errors.add("fluxnova.cluster.host must be a host name or IPv4 address");
            }
            if (cluster.getPort() < 1 || cluster.getPort() > 65535) {
                errors.add("fluxnova.cluster.port must be between 1 and 65535");
            }
            if (isBlank(cluster.getAdminUser())) {
                errors.add("fluxnova.cluster.admin-user is missing");
            }
            if (isBlank(cluster.getAdminPassword())) {
                errors.add("fluxnova.cluster.admin-password is missing "
                        + "(set it in tenants.yml or through fluxnova.runtime.admin-password)");
            }
            if (!DatabaseProvisioningService.DB_NAME_PATTERN.matcher(cluster.getAdminDatabase()).matches()) {
                errors.add("fluxnova.cluster.admin-database is not a valid database name");
            }
        }

        Set<String> ids = new HashSet<>();
        Set<String> databases = new HashSet<>();
        int index = 0;
        for (TenantConfigProperties.Tenant tenant : root.getTenants()) {
            String where = "fluxnova.tenants[" + index++ + "]";
            if (tenant == null) {
                errors.add(where + " is empty");
                continue;
            }
            if (isBlank(tenant.getId()) || !TENANT_ID.matcher(tenant.getId()).matches()) {
                errors.add(where + ".id must match " + TENANT_ID.pattern());
            } else if (!ids.add(tenant.getId())) {
                errors.add(where + ".id '" + tenant.getId() + "' is used more than once");
            }

            String dbName = tenant.getDbName();
            if (isBlank(dbName) || !DatabaseProvisioningService.DB_NAME_PATTERN.matcher(dbName).matches()) {
                errors.add(where + ".dbName must match " + DatabaseProvisioningService.DB_NAME_PATTERN.pattern());
            } else if (RESERVED_DATABASES.contains(dbName)) {
                errors.add(where + ".dbName '" + dbName + "' is a reserved system database");
            } else if (!databases.add(dbName)) {
                errors.add(where + ".dbName '" + dbName + "' is used by more than one tenant");
            }
        }

        if (!errors.isEmpty()) {
            throw new TenantConfigException("tenants.yml failed validation: " + String.join("; ", errors));
        }
    }

    /**
     * SnakeYAML's own messages quote the offending source line, which could be the line holding the admin password.
     * Build the message from the problem description and its position only.
     */
    private static String describe(RuntimeException e) {
        if (e instanceof MarkedYAMLException marked) {
            StringBuilder sb = new StringBuilder();
            if (marked.getContext() != null) {
                sb.append(marked.getContext()).append(": ");
            }
            sb.append(marked.getProblem());
            if (marked.getProblemMark() != null) {
                sb.append(" (line ").append(marked.getProblemMark().getLine() + 1)
                        .append(", column ").append(marked.getProblemMark().getColumn() + 1).append(')');
            }
            return sb.toString();
        }
        return e.getClass().getSimpleName();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Lets the YAML use {@code admin-user} for the bean property {@code adminUser} (camelCase keys keep working). */
    private static final class KebabCasePropertyUtils extends PropertyUtils {
        @Override
        public Property getProperty(Class<?> type, String name) {
            return super.getProperty(type, toCamelCase(name));
        }

        private static String toCamelCase(String name) {
            if (name.indexOf('-') < 0) {
                return name;
            }
            StringBuilder sb = new StringBuilder(name.length());
            boolean upperNext = false;
            for (char c : name.toCharArray()) {
                if (c == '-') {
                    upperNext = true;
                } else {
                    sb.append(upperNext ? Character.toUpperCase(c) : c);
                    upperNext = false;
                }
            }
            return sb.toString();
        }
    }
}
