package com.dbnav.driver.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * JDBC driver metadata entity.
 *
 * Maps to the jdbc_driver_registry table:
 *   id, db_type, version, driver_class, jar_filename, jar_path,
 *   file_size, is_active, uploaded_at, note
 *
 * UNIQUE constraint: (db_type, version, jar_filename)
 * Only one driver per db_type can be is_active=1 at a time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverInfo {

    private Long id;
    private String dbType;        // e.g. "oracle", "mysql", "postgresql", "sqlserver", "kingbase"
    private String version;       // e.g. "8", "8.0.33", "42.7.13"
    private String driverClass;   // e.g. "oracle.jdbc.OracleDriver"
    private String jarFilename;   // e.g. "ojdbc8.jar"
    private String jarPath;       // absolute or relative path to the JAR file
    private Long fileSize;        // file size in bytes
    private Boolean active;       // only one active per db_type
    private LocalDateTime uploadedAt;
    private String note;

    /**
     * Whether the JAR actually exists on disk.
     * Serialized to JSON as {@code jarPresent} so the console can flag
     * registered-but-missing drivers (e.g. metadata seeded before upload).
     */
    public boolean isJarPresent() {
        return jarPath != null && new java.io.File(jarPath).isFile();
    }
}
