package com.dbnav.datasource.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating/updating a data source.
 * Password is sent in plaintext and encrypted before storage.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionRequest {

    @NotBlank
    private String name;           // data source name (unique)

    @NotBlank
    private String dbType;         // "oracle", "mysql", "postgresql", "sqlserver", "kingbase"

    @NotBlank
    private String host;

    @NotNull
    private Integer port;          // use 0 for default port per db_type

    @NotBlank
    private String username;

    @NotBlank
    private String password;       // plaintext (encrypted on save)

    private String databaseName;  // target database/schema

    private String sid;           // Oracle SID (optional)

    private String serviceName;   // Oracle service name (optional)

    private String extraParams;   // additional JDBC URL params

    private String driverVersion; // explicitly select driver version (optional)

    private Integer poolSize;     // override pool size (optional)

    private String note;
}
