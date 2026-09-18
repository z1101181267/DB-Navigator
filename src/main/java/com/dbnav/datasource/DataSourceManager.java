package com.dbnav.datasource;

import com.dbnav.common.PasswordEncryptor;
import com.dbnav.config.DbNavProperties;
import com.dbnav.driver.DbTypeRegistry;
import com.dbnav.driver.DriverPathResolver;
import com.dbnav.driver.DriverRegistry;
import com.dbnav.driver.DynamicDriverLoader;
import com.dbnav.driver.model.DbTypeMeta;
import com.dbnav.driver.model.DriverInfo;
import com.dbnav.datasource.model.DataSourceInfo;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages database connection pools and CRUD for data source configurations.
 *
 * Responsibilities:
 *   - Stores data source configs in data_source table (passwords encrypted)
 *   - Creates HikariCP connection pools on demand
 *   - Uses DynamicDriverLoader to load JDBC drivers at runtime
 *   - Connection URLs built from db-types.json templates
 */
@Slf4j
@Component
public class DataSourceManager {

    private final JdbcTemplate jdbc;
    private final DriverRegistry driverRegistry;
    private final DynamicDriverLoader driverLoader;
    private final DriverPathResolver pathResolver;
    private final ConnectionUrlBuilder urlBuilder;
    private final DbNavProperties properties;
    private final DbTypeRegistry dbTypeRegistry;

    /** Cache: data_source_id -> HikariDataSource pool */
    private final Map<Long, HikariDataSource> poolCache = new ConcurrentHashMap<>();

    private static final RowMapper<DataSourceInfo> ROW_MAPPER = (rs, rowNum) -> DataSourceInfo.builder()
            .id(rs.getLong("id"))
            .name(rs.getString("name"))
            .dbType(rs.getString("db_type"))
            .host(rs.getString("host"))
            .port(rs.getInt("port"))
            .username(rs.getString("username"))
            .passwordEnc(rs.getString("password_enc"))
            .databaseName(rs.getString("database_name"))
            .sid(rs.getString("sid"))
            .serviceName(rs.getString("service_name"))
            .extraParams(rs.getString("extra_params"))
            .driverVersion(rs.getString("driver_version"))
            .poolSize(rs.getObject("pool_size", Integer.class))
            .status(rs.getString("status"))
            .createdAt(rs.getTimestamp("created_at") != null ?
                    rs.getTimestamp("created_at").toLocalDateTime() : null)
            .updatedAt(rs.getTimestamp("updated_at") != null ?
                    rs.getTimestamp("updated_at").toLocalDateTime() : null)
            .lastConnected(rs.getTimestamp("last_connected") != null ?
                    rs.getTimestamp("last_connected").toLocalDateTime() : null)
            .note(rs.getString("note"))
            .build();

    public DataSourceManager(JdbcTemplate jdbc,
                              DriverRegistry driverRegistry,
                              DynamicDriverLoader driverLoader,
                              DriverPathResolver pathResolver,
                              ConnectionUrlBuilder urlBuilder,
                              DbNavProperties properties,
                              DbTypeRegistry dbTypeRegistry) {
        this.jdbc = jdbc;
        this.driverRegistry = driverRegistry;
        this.driverLoader = driverLoader;
        this.pathResolver = pathResolver;
        this.urlBuilder = urlBuilder;
        this.properties = properties;
        this.dbTypeRegistry = dbTypeRegistry;
    }

    // ==================== 驱动解析 ====================

    /**
     * 连接所需的驱动信息。
     *
     * @param driverClass 驱动类名
     * @param version     版本标识（bundled 类型为 "bundled"）
     * @param jarFile     外部 JAR；bundled 类型为 null
     * @param bundled     true = 驱动随应用分发，已在 classpath 上
     */
    private record DriverPlan(String driverClass, String version, File jarFile, boolean bundled) {}

    /**
     * 解析连接计划。分两条路：
     *
     *   · bundled 类型（db-types.json 里 driver_bundled=true，目前只有自检用的 H2）——
     *     驱动本来就在应用 classpath 上，不需要用户放 JAR，也不需要驱动注册表里有记录。
     *   · 常规类型——走驱动注册表的激活版本，并做三级路径重定位。
     */
    private DriverPlan resolvePlan(DataSourceInfo ds) {
        DbTypeMeta meta = dbTypeRegistry.getDbMeta(ds.getDbType()).orElse(null);
        if (meta != null && Boolean.TRUE.equals(meta.getDriverBundled())) {
            String cls = meta.getDriverClassHint();
            if (cls == null || cls.isBlank()) {
                throw new IllegalStateException("库类型 " + ds.getDbType()
                        + " 标记为 driver_bundled 但未配置 driver_class_hint");
            }
            return new DriverPlan(cls, "bundled", null, true);
        }

        DriverInfo driver = resolveDriver(ds);
        if (driver == null) {
            throw new IllegalStateException("No JDBC driver registered for " + ds.getDbType()
                    + ". Please upload the driver JAR first.");
        }
        File jarFile = pathResolver.resolveJarPath(
                driver.getDbType(), driver.getVersion(), driver.getJarFilename());
        if (jarFile == null || !jarFile.exists()) {
            throw new IllegalStateException("Driver JAR not found for " + ds.getDbType()
                    + " v" + driver.getVersion() + ": " + driver.getJarFilename()
                    + " (expected under drivers/" + ds.getDbType() + "/" + driver.getVersion() + "/)");
        }
        return new DriverPlan(driver.getDriverClass(), driver.getVersion(), jarFile, false);
    }

    /** 按连接计划建立连接（bundled 与外部 JAR 两条路在此收敛） */
    private Connection openConnection(DataSourceInfo ds, DriverPlan plan) throws Exception {
        String url = urlBuilder.buildUrl(ds);
        String password = PasswordEncryptor.decrypt(ds.getPasswordEnc());
        return plan.bundled()
                ? driverLoader.connectBundled(ds.getDbType(), plan.version(),
                        plan.driverClass(), url, ds.getUsername(), password)
                : driverLoader.connect(ds.getDbType(), plan.version(), plan.jarFile(),
                        plan.driverClass(), url, ds.getUsername(), password);
    }

    // ==================== CRUD ====================

    public DataSourceInfo create(DataSourceInfo ds) {
        // Encrypt password
        ds.setPasswordEnc(PasswordEncryptor.encrypt(ds.getPasswordEnc()));
        ds.setStatus("OFFLINE");

        jdbc.update("""
                INSERT INTO data_source
                    (name, db_type, host, port, username, password_enc, database_name,
                     sid, service_name, extra_params, driver_version, pool_size, status, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                ds.getName(), ds.getDbType(), ds.getHost(), ds.getPort(),
                ds.getUsername(), ds.getPasswordEnc(), ds.getDatabaseName(),
                ds.getSid(), ds.getServiceName(), ds.getExtraParams(),
                ds.getDriverVersion(), ds.getPoolSize(), ds.getStatus(), ds.getNote());

        // Get inserted ID
        List<DataSourceInfo> inserted = jdbc.query(
                "SELECT * FROM data_source WHERE name=?", ROW_MAPPER, ds.getName());
        if (!inserted.isEmpty()) {
            log.info("Created data source: {} ({})", ds.getName(), ds.getDbType());
            return inserted.get(0);
        }
        throw new RuntimeException("Insert succeeded but row not found");
    }

    public DataSourceInfo update(DataSourceInfo ds) {
        // Re-encrypt if password changed
        if (ds.getPasswordEnc() != null && !ds.getPasswordEnc().isEmpty()
                && !ds.getPasswordEnc().startsWith("enc:")) {
            ds.setPasswordEnc(PasswordEncryptor.encrypt(ds.getPasswordEnc()));
        }

        jdbc.update("""
                UPDATE data_source SET
                    name=?, db_type=?, host=?, port=?, username=?, password_enc=?,
                    database_name=?, sid=?, service_name=?, extra_params=?,
                    driver_version=?, pool_size=?, note=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """,
                ds.getName(), ds.getDbType(), ds.getHost(), ds.getPort(),
                ds.getUsername(), ds.getPasswordEnc(), ds.getDatabaseName(),
                ds.getSid(), ds.getServiceName(), ds.getExtraParams(),
                ds.getDriverVersion(), ds.getPoolSize(), ds.getNote(), ds.getId());

        // Evict pool cache (config changed)
        evictPool(ds.getId());

        return findById(ds.getId()).orElseThrow();
    }

    public void delete(Long id) {
        evictPool(id);
        jdbc.update("DELETE FROM data_source WHERE id=?", id);
        log.info("Deleted data source id={}", id);
    }

    public List<DataSourceInfo> findAll() {
        return jdbc.query("SELECT * FROM data_source ORDER BY name", ROW_MAPPER);
    }

    public Optional<DataSourceInfo> findById(Long id) {
        List<DataSourceInfo> results = jdbc.query(
                "SELECT * FROM data_source WHERE id=?", ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<DataSourceInfo> findByName(String name) {
        List<DataSourceInfo> results = jdbc.query(
                "SELECT * FROM data_source WHERE name=?", ROW_MAPPER, name);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // ==================== Connection Management ====================

    /**
     * Test a database connection without creating a pool.
     * Backs the /api/test_db endpoint.
     */
    public Map<String, Object> testConnection(DataSourceInfo ds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dbType", ds.getDbType());
        result.put("host", ds.getHost());
        result.put("port", ds.getPort());

        long start = System.currentTimeMillis();
        try {
            DriverPlan plan = resolvePlan(ds);
            String url = urlBuilder.buildUrl(ds);

            try (Connection conn = openConnection(ds, plan)) {
                long elapsed = System.currentTimeMillis() - start;
                result.put("success", true);
                result.put("elapsedMs", elapsed);
                result.put("driverVersion", plan.version());
                result.put("driverClass", plan.driverClass());
                result.put("driverBundled", plan.bundled());
                result.put("jdbcUrl", maskUrlPassword(url));
                result.put("databaseProductName", conn.getMetaData().getDatabaseProductName());
                result.put("databaseProductVersion", conn.getMetaData().getDatabaseProductVersion());

                // Update status (only for persisted data sources)
                if (ds.getId() != null) {
                    jdbc.update("UPDATE data_source SET status='ONLINE', last_connected=CURRENT_TIMESTAMP WHERE id=?",
                            ds.getId());
                }
            }

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("elapsedMs", elapsed);
            result.put("error", e.getMessage());
            result.put("errorType", e.getClass().getSimpleName());

            // Update status
            if (ds.getId() != null) {
                jdbc.update("UPDATE data_source SET status='ERROR' WHERE id=?", ds.getId());
            }
            log.error("Connection test failed for {} ({})", ds.getName(), ds.getDbType(), e);
        }

        return result;
    }

    /**
     * Get or create a HikariCP connection pool for a data source.
     */
    public HikariDataSource getPool(DataSourceInfo ds) {
        return poolCache.computeIfAbsent(ds.getId(), id -> createPool(ds));
    }

    /**
     * Get a raw JDBC connection for ad-hoc query execution.
     */
    public Connection getConnection(Long dataSourceId) throws Exception {
        DataSourceInfo ds = findById(dataSourceId)
                .orElseThrow(() -> new IllegalArgumentException("Data source not found: " + dataSourceId));

        // Try pool first
        HikariDataSource pool = poolCache.get(dataSourceId);
        if (pool != null && !pool.isClosed()) {
            return pool.getConnection();
        }

        // Fall back to direct connection
        return openConnection(ds, resolvePlan(ds));
    }

    // ==================== Internal helpers ====================

    private DriverInfo resolveDriver(DataSourceInfo ds) {
        // If a specific version is requested, use it
        if (ds.getDriverVersion() != null && !ds.getDriverVersion().isEmpty()) {
            return driverRegistry.getDriver(ds.getDbType(), ds.getDriverVersion())
                    .orElse(null);
        }
        // Otherwise use the active driver
        return driverRegistry.getActiveDriver(ds.getDbType()).orElse(null);
    }

    private HikariDataSource createPool(DataSourceInfo ds) {
        DriverPlan plan = resolvePlan(ds);

        // bundled 类型无需预加载外部 JAR（驱动已在应用 classpath 上）
        if (!plan.bundled()) {
            driverLoader.loadDriver(ds.getDbType(), plan.version(), plan.jarFile(), plan.driverClass());
        } else {
            driverLoader.loadBundledDriver(ds.getDbType(), plan.version(), plan.driverClass());
        }

        String url = urlBuilder.buildUrl(ds);
        String password = PasswordEncryptor.decrypt(ds.getPasswordEnc());

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(ds.getUsername());
        config.setPassword(password);
        config.setDriverClassName(plan.driverClass());

        // Pool settings
        int poolSize = ds.getPoolSize() != null ? ds.getPoolSize() : properties.getPool().getMaximumPoolSize();
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(properties.getPool().getMinimumIdle());
        config.setConnectionTimeout(properties.getPool().getConnectionTimeout());
        config.setIdleTimeout(properties.getPool().getIdleTimeout());
        config.setMaxLifetime(properties.getPool().getMaxLifetime());
        config.setPoolName("dbnav-" + ds.getName());

        HikariDataSource pool = new HikariDataSource(config);
        log.info("Created connection pool for '{}' ({}) size={}", ds.getName(), ds.getDbType(), poolSize);
        return pool;
    }

    private void evictPool(Long dataSourceId) {
        HikariDataSource pool = poolCache.remove(dataSourceId);
        if (pool != null && !pool.isClosed()) {
            pool.close();
            log.info("Closed connection pool for data source id={}", dataSourceId);
        }
    }

    /**
     * Mask password in JDBC URL for logging/display.
     */
    private String maskUrlPassword(String url) {
        return url.replaceAll("password=[^&;]*", "password=****");
    }
}
