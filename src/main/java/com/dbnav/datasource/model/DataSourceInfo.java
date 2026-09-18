package com.dbnav.datasource.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Managed data source entity — represents a configured database connection.
 *
 * Stored in the data_source table. Passwords are AES-encrypted at rest
 * (AES-GCM with a fresh random IV per record).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceInfo {

    private Long id;
    private String name;              // human-friendly name (unique)
    private String dbType;            // "oracle", "mysql", "postgresql", "sqlserver", "kingbase"
    private String host;
    private Integer port;
    private String username;
    private String passwordEnc;      // encrypted password (AES-256-GCM)
    private String databaseName;     // target database/schema name
    private String sid;              // Oracle SID (optional, alternative to service_name)
    private String serviceName;      // Oracle service name (optional)
    private String extraParams;      // additional JDBC URL query params
    private String driverVersion;    // explicitly selected driver version (optional)
    private Integer poolSize;        // connection pool size override
    private String status;           // ONLINE / OFFLINE / ERROR
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastConnected;
    private String note;
}
