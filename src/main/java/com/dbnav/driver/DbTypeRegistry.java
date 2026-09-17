package com.dbnav.driver;

import com.dbnav.config.ConfigJsonMapper;
import com.dbnav.config.DbNavProperties;
import com.dbnav.driver.model.DbTypeMeta;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Database type metadata registry.
 *
 * Aligned with RaccoonX's dbtype_registry.py:
 *   - Loads builtin types from JSON (db-types.json, equivalent of builtin_types.json)
 *   - Provides getDbMeta(db_type) lookup
 *   - Provides getDriverClassHint(db_type) for scan/seed operations
 *   - In-memory cache (like RaccoonX's _ALL_CACHE)
 *
 * Supported types: oracle, mysql, postgresql, sqlserver, kingbase
 */
@Slf4j
@Component
public class DbTypeRegistry {

    private final DbNavProperties properties;
    private final ObjectMapper objectMapper = ConfigJsonMapper.get();

    private List<DbTypeMeta> allTypes = new ArrayList<>();
    private Map<String, DbTypeMeta> typeMap = new ConcurrentHashMap<>();

    public DbTypeRegistry(DbNavProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        loadBuiltinTypes();
    }

    /**
     * Load built-in database types from JSON resource.
     * Aligned with RaccoonX's load_builtin_types().
     */
    public void loadBuiltinTypes() {
        try {
            DefaultResourceLoader loader = new DefaultResourceLoader();
            Resource resource = loader.getResource(properties.getDbTypesConfig());

            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> root = objectMapper.readValue(is,
                        new TypeReference<Map<String, Object>>() {});
                Object typesObj = root.get("types");
                allTypes = objectMapper.convertValue(typesObj,
                        new TypeReference<List<DbTypeMeta>>() {});
            }

            typeMap.clear();
            for (DbTypeMeta meta : allTypes) {
                typeMap.put(meta.getDbType(), meta);
            }

            log.info("Loaded {} database type definitions: {}",
                    allTypes.size(),
                    allTypes.stream().map(DbTypeMeta::getDbType).toList());

        } catch (Exception e) {
            log.error("Failed to load database types from {}", properties.getDbTypesConfig(), e);
        }
    }

    /**
     * Get metadata for a specific db_type.
     * Aligned with RaccoonX's get_db_meta().
     */
    public Optional<DbTypeMeta> getDbMeta(String dbType) {
        return Optional.ofNullable(typeMap.get(dbType));
    }

    /**
     * Get all registered database types.
     * Aligned with RaccoonX's load_all_db_types().
     */
    public List<DbTypeMeta> getAllTypes() {
        return Collections.unmodifiableList(allTypes);
    }

    /**
     * Get the driver class hint for a db_type.
     * Used by directory scanner when driver_class is not explicitly known.
     */
    public String getDriverClassHint(String dbType) {
        DbTypeMeta meta = typeMap.get(dbType);
        return meta != null ? meta.getDriverClassHint() : null;
    }

    /**
     * Get the JDBC URL template for a db_type.
     */
    public String getUrlTemplate(String dbType) {
        DbTypeMeta meta = typeMap.get(dbType);
        return meta != null ? meta.getUrlTemplate() : null;
    }

    /**
     * Get the alternative JDBC URL template (e.g. Oracle SID mode).
     */
    public String getUrlTemplateSid(String dbType) {
        DbTypeMeta meta = typeMap.get(dbType);
        return meta != null ? meta.getUrlTemplateSid() : null;
    }

    /**
     * Get the default port for a db_type.
     */
    public int getDefaultPort(String dbType) {
        DbTypeMeta meta = typeMap.get(dbType);
        return meta != null && meta.getPort() != null ? meta.getPort() : 0;
    }

    /**
     * Check if a db_type is a known built-in type.
     */
    public boolean isKnownType(String dbType) {
        return typeMap.containsKey(dbType);
    }
}
