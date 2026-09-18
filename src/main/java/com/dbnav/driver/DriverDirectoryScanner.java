package com.dbnav.driver;

import com.dbnav.driver.model.DriverInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scans the drivers/ directory and auto-registers found JARs.
 *
 * Handles three layout patterns:
 *     1. drivers/<db_type>/<version>/<jar>  — standard (preferred)
 *     2. drivers/<db_type>/<jar>            — legacy flat
 *     3. drivers/<jar>                      — scattered root (skipped, can't classify)
 *
 *   - Idempotent: skips already-registered drivers (UNIQUE conflict)
 *   - driver_class from DbTypeMeta driver_class_hint
 *   - After scanning, ensures at least one driver per db_type is active
 */
@Slf4j
@Component
public class DriverDirectoryScanner {

    private final DriverPathResolver pathResolver;
    private final DriverRegistry registry;
    private final DbTypeRegistry dbTypeRegistry;

    public DriverDirectoryScanner(DriverPathResolver pathResolver,
                                   DriverRegistry registry,
                                   DbTypeRegistry dbTypeRegistry) {
        this.pathResolver = pathResolver;
        this.registry = registry;
        this.dbTypeRegistry = dbTypeRegistry;
    }

    /**
    /**
     * Scan the drivers/ directory and reconcile the registry with what is on disk:
     * register JARs not yet known, and refresh the observed path/size of records
     * whose JAR has since appeared or been replaced.
     *
     * @return the number of newly registered drivers
     */
    public int scanAndRegister() {
        File driversDir = pathResolver.getDriversDir();
        if (!driversDir.exists() || !driversDir.isDirectory()) {
            log.info("Drivers directory does not exist, skipping scan: {}", driversDir.getAbsolutePath());
            return 0;
        }

        File[] typeDirs = driversDir.listFiles(File::isDirectory);
        if (typeDirs == null || typeDirs.length == 0) {
            log.info("No subdirectories in drivers/, nothing to scan");
            return 0;
        }

        int registered = 0;
        int refreshed = 0;
        for (File typeDir : typeDirs) {
            String dbType = typeDir.getName();
            String driverClassHint = dbTypeRegistry.getDriverClassHint(dbType);

            // Layout 1: drivers/<db_type>/<version>/<jar>
            File[] versionDirs = typeDir.listFiles(File::isDirectory);
            if (versionDirs != null) {
                for (File versionDir : versionDirs) {
                    String version = versionDir.getName();
                    File[] jars = versionDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
                    if (jars == null) continue;

                    for (File jar : jars) {
                        if (!registry.findByTypeVersionJar(dbType, version, jar.getName()).isPresent()) {
                            registry.insertRaw(
                                    dbType, version, driverClassHint,
                                    jar.getName(), jar.getAbsolutePath(),
                                    jar.length(), false,
                                    "Auto-registered by directory scan"
                            );
                            registered++;
                            log.info("Scan registered: {}/{} -> {}", dbType, version, jar.getName());
                        } else {
                            refreshed += refreshIfDrifted(dbType, version, jar);
                        }
                    }
                }
            }

            // Layout 2: drivers/<db_type>/<jar> (legacy flat)
            File[] flatJars = typeDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
            if (flatJars != null && flatJars.length > 0) {
                for (File jar : flatJars) {
                    // For flat layout, use filename as version hint or "unknown"
                    String version = inferVersionFromFilename(jar.getName(), dbType);
                    if (version == null) continue;

                    if (!registry.findByTypeVersionJar(dbType, version, jar.getName()).isPresent()) {
                        registry.insertRaw(
                                dbType, version, driverClassHint,
                                jar.getName(), jar.getAbsolutePath(),
                                jar.length(), false,
                                "Auto-registered by directory scan (flat layout)"
                        );
                        registered++;
                        log.info("Scan registered (flat): {}/{} -> {}", dbType, version, jar.getName());
                    } else {
                        refreshed += refreshIfDrifted(dbType, version, jar);
                    }
                }
            }
        }

        // Ensure at least one driver per db_type is active
        if (registered > 0 || registry.isEmpty()) {
            registry.ensureActivation();
        }

        log.info("Directory scan complete: {} new drivers registered, {} records refreshed",
                registered, refreshed);
        return registered;
    }

    /**
     * A record may have been seeded before its JAR existed, carrying the seed's
     * declared {@code file_size}. Once the JAR is on disk — or is later replaced
     * with a different build — the registry would otherwise display a stale size
     * forever (the console renders {@code fileSize}).
     *
     * <p>Only observed facts are reconciled here; user-owned metadata
     * ({@code driver_class}, {@code note}, {@code is_active}) is left alone.
     *
     * @return 1 if the record was updated, else 0
     */
    private int refreshIfDrifted(String dbType, String version, File jar) {
        return registry.findByTypeVersionJar(dbType, version, jar.getName())
                .filter(d -> registry.refreshJarFacts(d.getId(), jar.getAbsolutePath(), jar.length()))
                .map(d -> {
                    log.info("Scan refreshed stale metadata: {}/{} -> {} ({} bytes)",
                            dbType, version, jar.getName(), jar.length());
                    return 1;
                })
                .orElse(0);
    }

    /**
     * Attempt to infer version from JAR filename.
     * E.g. "ojdbc8.jar" -> "8", "mysql-connector-j-8.0.33.jar" -> "8.0.33"
     */
    private String inferVersionFromFilename(String filename, String dbType) {
        // Remove .jar extension
        String base = filename.substring(0, filename.length() - 4);

        // Try to extract version pattern (digits with optional dots)
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)*)").matcher(base);
        if (m.find()) {
            return m.group(1);
        }
        return null; // can't infer, skip
    }
}
