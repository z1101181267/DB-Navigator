package com.dbnav.datasource;

import com.dbnav.driver.DbTypeRegistry;
import com.dbnav.driver.model.DbTypeMeta;
import com.dbnav.datasource.model.DataSourceInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Builds JDBC connection URLs from database type templates.
 *
 * Each db_type has a URL template defined in db-types.json:
 *   - Oracle:  jdbc:oracle:thin:@//{host}:{port}/{service_name}
 *              jdbc:oracle:thin:@{host}:{port}:{sid}  (SID mode)
 *   - MySQL:   jdbc:mysql://{host}:{port}/{database}?useSSL=false&...
 *   - PG:      jdbc:postgresql://{host}:{port}/{database}
 *   - MSSQL:   jdbc:sqlserver://{host}:{port};databaseName={database};encrypt=false;...
 *   - Kingbase: jdbc:kingbase8://{host}:{port}/{database}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionUrlBuilder {

    private final DbTypeRegistry dbTypeRegistry;

    /**
     * Build a JDBC connection URL for the given data source configuration.
     *
     * @param ds the data source info
     * @return fully-formed JDBC URL
     */
    public String buildUrl(DataSourceInfo ds) {
        Optional<DbTypeMeta> metaOpt = dbTypeRegistry.getDbMeta(ds.getDbType());
        if (metaOpt.isEmpty()) {
            throw new IllegalArgumentException("Unknown db_type: " + ds.getDbType());
        }

        DbTypeMeta meta = metaOpt.get();

        // Oracle: support both SID and service_name modes
        if ("oracle".equals(ds.getDbType())) {
            return buildOracleUrl(meta, ds);
        }

        // Standard template substitution
        String template = meta.getUrlTemplate();
        if (template == null) {
            throw new IllegalStateException("No URL template for db_type: " + ds.getDbType());
        }

        String url = template
                .replace("{host}", ds.getHost())
                .replace("{port}", String.valueOf(ds.getPort()))
                .replace("{database}", ds.getDatabaseName() != null ? ds.getDatabaseName() : "");

        // Append extra params if provided.
        // Separator depends on the URL style: MySQL/Oracle use '?', SQL Server uses ';'.
        if (ds.getExtraParams() != null && !ds.getExtraParams().isEmpty()) {
            if (url.contains("?")) {
                url += "&" + ds.getExtraParams();
            } else if (url.contains(";")) {
                url += ";" + ds.getExtraParams();
            } else {
                url += "?" + ds.getExtraParams();
            }
        }

        return url;
    }

    /**
     * Build Oracle JDBC URL — supports both SID and service_name modes.
     *
     * service_name mode is the default; SID mode is an alternative.
     */
    private String buildOracleUrl(DbTypeMeta meta, DataSourceInfo ds) {
        String host = ds.getHost();
        int port = ds.getPort();

        if (ds.getSid() != null && !ds.getSid().isEmpty()) {
            // SID mode: jdbc:oracle:thin:@host:port:sid
            String template = meta.getUrlTemplateSid() != null ?
                    meta.getUrlTemplateSid() : "jdbc:oracle:thin:@{host}:{port}:{sid}";
            return template
                    .replace("{host}", host)
                    .replace("{port}", String.valueOf(port))
                    .replace("{sid}", ds.getSid());
        } else {
            // Service name mode (default): jdbc:oracle:thin:@//host:port/service_name
            String serviceName = ds.getServiceName() != null ? ds.getServiceName() : "ORCL";
            String template = meta.getUrlTemplate() != null ?
                    meta.getUrlTemplate() : "jdbc:oracle:thin:@//{host}:{port}/{service_name}";
            return template
                    .replace("{host}", host)
                    .replace("{port}", String.valueOf(port))
                    .replace("{service_name}", serviceName);
        }
    }

    /**
     * Get the default port for a db_type.
     */
    public int getDefaultPort(String dbType) {
        return dbTypeRegistry.getDefaultPort(dbType);
    }
}
