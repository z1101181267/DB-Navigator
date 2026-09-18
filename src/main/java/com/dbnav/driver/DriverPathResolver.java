package com.dbnav.driver;

import com.dbnav.config.DbNavProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves and relocates JDBC driver JAR paths.
 *
 * Solves the problem where an absolute jar_path stored in the registry becomes
 * invalid after packaging
 * or moving the application. Uses three-layer fallback scan:
 *
 *   1) drivers/<db_type>/<version>/<jar_filename>   — standard layout
 *   2) drivers/<db_type>/<jar_filename>              — legacy flat layout
 *   3) drivers/<jar_filename>                        — scattered root (last resort)
 */
@Slf4j
@Component
public class DriverPathResolver {

    private final DbNavProperties properties;

    public DriverPathResolver(DbNavProperties properties) {
        this.properties = properties;
    }

    /**
     * Get the root drivers directory as a File, creating it if necessary.
     *
     * <p>返回的是**规范化后的绝对路径**。这一点很重要：配置里默认值是 {@code ./drivers}，
     * 而 {@link File#getAbsolutePath()} 并不会折叠 {@code ./}，会得到
     * {@code E:\dev\db-navigator\.\drivers} 这种带冗余片段的路径，并被原样写进
     * {@code jarPath} 字段回给前端展示。这里统一 normalize 掉。
     */
    public File getDriversDir() {
        File dir = new File(properties.getDriversDir())
                .toPath().toAbsolutePath().normalize().toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Resolve the actual JAR file path for a registered driver.
     * Falls back through three layout patterns if the stored path is invalid.
     *
     * @param dbType      database type (e.g. "oracle")
     * @param version     driver version (e.g. "8")
     * @param jarFilename JAR file name (e.g. "ojdbc8.jar")
     * @return resolved File if found, null otherwise
     */
    public File resolveJarPath(String dbType, String version, String jarFilename) {
        // 0. If the stored path is valid, use it directly
        // (caller passes the stored path as jarFilename when calling this method)

        File driversDir = getDriversDir();

        // 1. Standard layout: drivers/<db_type>/<version>/<jar_filename>
        File standard = Paths.get(driversDir.getAbsolutePath(), dbType, version, jarFilename).toFile();
        if (standard.isFile()) {
            return standard;
        }

        // 2. Legacy flat layout: drivers/<db_type>/<jar_filename>
        File legacy = Paths.get(driversDir.getAbsolutePath(), dbType, jarFilename).toFile();
        if (legacy.isFile()) {
            return legacy;
        }

        // 3. Scattered root: drivers/<jar_filename>
        File scattered = Paths.get(driversDir.getAbsolutePath(), jarFilename).toFile();
        if (scattered.isFile()) {
            return scattered;
        }

        log.warn("JAR not found in any layout: dbType={}, version={}, jar={}",
                dbType, version, jarFilename);
        return null;
    }

    /**
     * Build the target directory for a new driver upload.
     * Format: drivers/<db_type>/<version>/
     */
    public File getTargetDir(String dbType, String version) {
        return Paths.get(getDriversDir().getAbsolutePath(), dbType, version).toFile();
    }

    /**
     * Get the relative path from the drivers directory root.
     */
    public String getRelativePath(String dbType, String version, String jarFilename) {
        return dbType + File.separator + version + File.separator + jarFilename;
    }
}
