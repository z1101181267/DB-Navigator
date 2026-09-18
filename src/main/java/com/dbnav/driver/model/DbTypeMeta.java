package com.dbnav.driver.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Database type metadata, loaded from db-types.json.
 *
 * Each managed database type has connection defaults, a JDBC URL template,
 * and a driver class hint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DbTypeMeta {

    private String dbType;              // "oracle", "mysql", etc.
    private String label;               // "Oracle", "MySQL", etc.
    private Integer port;               // default port: 1521, 3306, etc.
    private String user;               // default username
    private String defaultDatabase;     // default database name
    private String icon;               // icon file name
    private String emoji;              // emoji for UI
    private String description;         // human-readable description
    private String protocol;            // "oracle", "mysql", "pg", "sqlserver", "other"
    private Boolean sqlEditor;          // whether SQL editor is enabled
    private Boolean showDatabaseField;  // whether to show database field in connection form
    private String driverClassHint;     // e.g. "oracle.jdbc.OracleDriver"
    /**
     * 驱动随应用一起分发（已在应用 classpath 上），无需往 drivers/ 目录放 JAR。
     * 目前只有内置自检用的 H2 为 true。
     */
    private Boolean driverBundled;
    private String urlTemplate;         // JDBC URL template with {host}, {port}, {database}
    private String urlTemplateSid;      // alternative URL template (Oracle SID mode)
    private String extraParamsExample;  // example extra params
}
