package com.dbnav.driver;

import com.dbnav.driver.model.DriverInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JDBC Driver Registry — core CRUD + activation logic.
 *
 * Operations:
 *   - add_driver:           validates + moves JAR + INSERT + auto-activate
 *   - delete_driver:        DELETE + auto-activate next driver
 *   - activate_driver:      set is_active=0 for same db_type, then =1 for target
 *   - get_active_driver:    SELECT WHERE db_type=? AND is_active=1
 *   - get_driver:           SELECT WHERE db_type=? [AND version=?]
 *   - list_drivers:         SELECT all (optionally filtered by db_type)
 *   - scanDisk:             scan disk and auto-register (delegated to DriverDirectoryScanner)
 *   - seedIfEmpty:          import from JSON if table is empty (delegated to DriverSeedLoader)
 *
 * UNIQUE constraint: (db_type, version, jar_filename)
 * Activation rule: only one driver per db_type can be is_active=1.
 */
@Slf4j
@Repository
public class DriverRegistry {

    private final JdbcTemplate jdbc;
    private final DriverPathResolver pathResolver;

    private static final RowMapper<DriverInfo> ROW_MAPPER = (rs, rowNum) -> DriverInfo.builder()
            .id(rs.getLong("id"))
            .dbType(rs.getString("db_type"))
            .version(rs.getString("version"))
            .driverClass(rs.getString("driver_class"))
            .jarFilename(rs.getString("jar_filename"))
            .jarPath(rs.getString("jar_path"))
            .fileSize(rs.getLong("file_size"))
            .active(rs.getInt("is_active") == 1)
            .uploadedAt(rs.getTimestamp("uploaded_at") != null ?
                    rs.getTimestamp("uploaded_at").toLocalDateTime() : null)
            .note(rs.getString("note"))
            .build();

    public DriverRegistry(JdbcTemplate jdbc, DriverPathResolver pathResolver) {
        this.jdbc = jdbc;
        this.pathResolver = pathResolver;
    }

    /**
     * Register a new driver.
     *
     * Steps:
     *   1. Validate db_type and version (regex)
     *   2. Validate filename (no path separators, no ..)
     *   3. Move JAR to drivers/<db_type>/<version>/
     *   4. INSERT into registry
     *   5. Auto-activate if no active driver exists for this db_type
     */
    public DriverInfo addDriver(String dbType, String version, String driverClass,
                                File sourceJar, String originalFilename, String note) {
        // 1. Validate db_type
        if (dbType == null || !dbType.matches("^[a-z][a-z0-9_]{0,63}$")) {
            throw new IllegalArgumentException("Invalid db_type: " + dbType +
                    " (must be lowercase alphanumeric + underscore, max 64 chars)");
        }

        // 2. Validate version
        if (version == null || !version.matches("^[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}$")) {
            throw new IllegalArgumentException("Invalid version: " + version +
                    " (alphanumeric + ._+-, max 64 chars)");
        }

        // 3. Validate filename (defense against path traversal)
        // Check BEFORE basename to prevent traversal
        if (originalFilename == null
                || originalFilename.contains("/")
                || originalFilename.contains("\\")
                || originalFilename.contains("..")
                || !originalFilename.toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("Invalid JAR filename: must end with .jar, " +
                    "no path separators or .. allowed");
        }

        // 4. Move JAR to drivers/<db_type>/<version>/
        File targetDir = pathResolver.getTargetDir(dbType, version);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        File targetJar = new File(targetDir, originalFilename);

        try {
            if (!sourceJar.getAbsoluteFile().equals(targetJar.getAbsoluteFile())) {
                moveOrCopy(sourceJar, targetJar);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to move JAR to " + targetJar, e);
        }

        long fileSize = targetJar.length();
        String jarPath = targetJar.getAbsolutePath();

        // 5. INSERT into registry (UNIQUE constraint will catch duplicates)
        try {
            jdbc.update("""
                    INSERT INTO jdbc_driver_registry
                        (db_type, version, driver_class, jar_filename, jar_path, file_size, is_active, note)
                    VALUES (?, ?, ?, ?, ?, ?, 0, ?)
                    """, dbType, version, driverClass, originalFilename, jarPath, fileSize, note);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException(
                    "Driver already registered: " + dbType + " v" + version +
                    " jar=" + originalFilename + " (UNIQUE constraint)");
        }

        // Get the inserted ID
        DriverInfo inserted = findByTypeVersionJar(dbType, version, originalFilename)
                .orElseThrow(() -> new RuntimeException("Insert succeeded but row not found"));

        // 6. Auto-activate if no active driver for this db_type
        int activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jdbc_driver_registry WHERE db_type=? AND is_active=1",
                Integer.class, dbType);
        if (activeCount == 0) {
            jdbc.update("UPDATE jdbc_driver_registry SET is_active=1 WHERE id=?", inserted.getId());
            inserted.setActive(true);
            log.info("Auto-activated driver: {} v{} (first driver for this type)", dbType, version);
        }

        log.info("Registered driver: {} v{} -> {}", dbType, version, originalFilename);
        return inserted;
    }

    /**
     * Delete a driver:
     *   - DELETE from registry
     *   - If deleted driver was active, auto-activate next (uploaded_at DESC)
     *   - Delete JAR file from disk
     */
    public void deleteDriver(Long driverId) {
        Optional<DriverInfo> opt = findById(driverId);
        if (opt.isEmpty()) {
            throw new IllegalArgumentException("Driver not found: id=" + driverId);
        }

        DriverInfo driver = opt.get();
        boolean wasActive = Boolean.TRUE.equals(driver.getActive());

        // Delete from registry
        jdbc.update("DELETE FROM jdbc_driver_registry WHERE id=?", driverId);

        // Delete JAR file from disk
        File jarFile = new File(driver.getJarPath());
        if (jarFile.exists()) {
            try {
                Files.delete(jarFile.toPath());
                log.info("Deleted JAR file: {}", jarFile.getAbsolutePath());
            } catch (Exception e) {
                log.warn("Failed to delete JAR file: {} (registry entry deleted)", jarFile, e);
            }
        }

        // Auto-activate next driver if the deleted one was active
        if (wasActive) {
            List<DriverInfo> remaining = jdbc.query(
                    "SELECT * FROM jdbc_driver_registry WHERE db_type=? ORDER BY uploaded_at DESC LIMIT 1",
                    ROW_MAPPER, driver.getDbType());
            if (!remaining.isEmpty()) {
                jdbc.update("UPDATE jdbc_driver_registry SET is_active=1 WHERE id=?",
                        remaining.get(0).getId());
                log.info("Auto-activated replacement: {} v{}",
                        remaining.get(0).getDbType(), remaining.get(0).getVersion());
            }
        }

        log.info("Deleted driver: {} v{} (id={})", driver.getDbType(), driver.getVersion(), driverId);
    }

    /**
     * Activate a specific driver version for a db_type.
     *   1. Set is_active=0 for ALL drivers of this db_type
     *   2. Set is_active=1 for the target driver
     */
    public void activateDriver(Long driverId) {
        DriverInfo driver = findById(driverId)
                .orElseThrow(() -> new IllegalArgumentException("Driver not found: id=" + driverId));

        jdbc.update("UPDATE jdbc_driver_registry SET is_active=0 WHERE db_type=?", driver.getDbType());
        jdbc.update("UPDATE jdbc_driver_registry SET is_active=1 WHERE id=?", driverId);

        log.info("Activated driver: {} v{} (id={})", driver.getDbType(), driver.getVersion(), driverId);
    }

    /**
     * Get the active driver for a db_type.
     */
    public Optional<DriverInfo> getActiveDriver(String dbType) {
        List<DriverInfo> results = jdbc.query(
                "SELECT * FROM jdbc_driver_registry WHERE db_type=? AND is_active=1 LIMIT 1",
                ROW_MAPPER, dbType);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Get a specific driver by db_type and optional version.
     */
    public Optional<DriverInfo> getDriver(String dbType, String version) {
        String sql;
        Object[] args;
        if (version != null && !version.isEmpty()) {
            sql = "SELECT * FROM jdbc_driver_registry WHERE db_type=? AND version=? " +
                  "ORDER BY is_active DESC, uploaded_at DESC LIMIT 1";
            args = new Object[]{dbType, version};
        } else {
            sql = "SELECT * FROM jdbc_driver_registry WHERE db_type=? AND is_active=1 LIMIT 1";
            args = new Object[]{dbType};
        }
        List<DriverInfo> results = jdbc.query(sql, ROW_MAPPER, args);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * List all drivers, optionally filtered by db_type.
     */
    public List<DriverInfo> listDrivers(String dbType) {
        if (dbType != null && !dbType.isEmpty()) {
            return jdbc.query(
                    "SELECT * FROM jdbc_driver_registry WHERE db_type=? ORDER BY is_active DESC, uploaded_at DESC",
                    ROW_MAPPER, dbType);
        }
        return jdbc.query(
                "SELECT * FROM jdbc_driver_registry ORDER BY db_type, is_active DESC, uploaded_at DESC",
                ROW_MAPPER);
    }

    /**
     * List all distinct db_types that have at least one registered driver.
     */
    public List<String> listDbTypesWithDrivers() {
        return jdbc.queryForList(
                "SELECT DISTINCT db_type FROM jdbc_driver_registry ORDER BY db_type",
                String.class);
    }

    /**
     * Check if the registry is empty (for seed loading decision).
     */
    public boolean isEmpty() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jdbc_driver_registry", Integer.class);
        return count == null || count == 0;
    }

    // ---- Helper queries ----

    /**
     * Move a file, falling back to copy+delete when the source and target live
     * on different filesystems (e.g. uploaded temp file on C: while the drivers
     * directory is on E:). {@code Files.move} throws FileSystemException in that
     * case on Windows, so we degrade gracefully.
     */
    private void moveOrCopy(File source, File target) throws java.io.IOException {
        try {
            Files.move(source.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.FileSystemException e) {
            log.debug("Cross-filesystem move detected ({} -> {}), falling back to copy+delete",
                    source, target);
            Files.copy(source.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(source.toPath());
        }
    }

    public Optional<DriverInfo> findById(Long id) {
        List<DriverInfo> results = jdbc.query(
                "SELECT * FROM jdbc_driver_registry WHERE id=?", ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<DriverInfo> findByTypeVersionJar(String dbType, String version, String jarFilename) {
        List<DriverInfo> results = jdbc.query(
                "SELECT * FROM jdbc_driver_registry WHERE db_type=? AND version=? AND jar_filename=?",
                ROW_MAPPER, dbType, version, jarFilename);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Direct INSERT for seed loading (bypasses file move logic).
     * Used by DriverSeedLoader and DriverDirectoryScanner.
     */
    public void insertRaw(String dbType, String version, String driverClass,
                          String jarFilename, String jarPath, long fileSize,
                          boolean isActive, String note) {
        try {
            jdbc.update("""
                    INSERT INTO jdbc_driver_registry
                        (db_type, version, driver_class, jar_filename, jar_path, file_size, is_active, note)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, dbType, version, driverClass, jarFilename, jarPath, fileSize,
                    isActive ? 1 : 0, note != null ? note : "");
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Idempotent: skip if already registered
            log.debug("Driver already registered (skipped): {} v{} {}", dbType, version, jarFilename);
        }
    }

    /**
     * Refresh the <em>observed</em> facts of an existing record — the JAR's
     * on-disk path and size.
     *
     * <p>Deliberately narrow: {@code driver_class} / {@code note} / {@code is_active}
     * are user-owned metadata and must not be touched by a directory scan.
     * The scan only reconciles what it can actually observe.
     *
     * <p>Needed because a record may be seeded before its JAR exists (seed import
     * registers metadata only, carrying the seed's declared {@code file_size}).
     * Once the JAR lands on disk — or is later replaced with a different build —
     * the registry would otherwise keep displaying a stale size forever.
     *
     * @return true if anything actually changed
     */
    public boolean refreshJarFacts(Long driverId, String jarPath, long fileSize) {
        int updated = jdbc.update("""
                UPDATE jdbc_driver_registry
                   SET jar_path = ?, file_size = ?
                 WHERE id = ?
                   AND (jar_path IS DISTINCT FROM ? OR file_size IS DISTINCT FROM ?)
                """, jarPath, fileSize, driverId, jarPath, fileSize);
        return updated > 0;
    }

    /**
     * Ensure at least one driver per db_type is active.
     * Called after seed loading or directory scanning.
     */
    public void ensureActivation() {
        List<String> types = listDbTypesWithDrivers();
        for (String dbType : types) {
            Integer activeCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM jdbc_driver_registry WHERE db_type=? AND is_active=1",
                    Integer.class, dbType);
            if (activeCount == null || activeCount == 0) {
                // Activate the first one (prefer version-subdirectory layout over root)
                List<DriverInfo> candidates = jdbc.query(
                        "SELECT * FROM jdbc_driver_registry WHERE db_type=? " +
                        "ORDER BY uploaded_at DESC LIMIT 1",
                        ROW_MAPPER, dbType);
                if (!candidates.isEmpty()) {
                    jdbc.update("UPDATE jdbc_driver_registry SET is_active=1 WHERE id=?",
                            candidates.get(0).getId());
                    log.info("Auto-activated: {} v{}", dbType, candidates.get(0).getVersion());
                }
            }
        }
    }
}
