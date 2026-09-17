package com.dbnav.controller;

import com.dbnav.common.Result;
import com.dbnav.datasource.ConnectionUrlBuilder;
import com.dbnav.datasource.DataSourceManager;
import com.dbnav.datasource.model.ConnectionRequest;
import com.dbnav.datasource.model.DataSourceInfo;
import com.dbnav.driver.DbTypeRegistry;
import com.dbnav.driver.model.DbTypeMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for data source (database connection) management.
 *
 * Endpoints:
 *   GET    /api/datasources                — list all data sources
 *   GET    /api/datasources/{id}           — get a data source by ID
 *   POST   /api/datasources                — create a new data source
 *   PUT    /api/datasources/{id}           — update a data source
 *   DELETE /api/datasources/{id}          — delete a data source
 *   POST   /api/datasources/{id}/test     — test connection
 *   GET    /api/datasources/types          — list db types with metadata
 *   GET    /api/datasources/defaults/{dbType} — get default connection params
 */
@RestController
@RequestMapping("/api/datasources")
@RequiredArgsConstructor
public class DataSourceController {

    private final DataSourceManager dataSourceManager;
    private final DbTypeRegistry dbTypeRegistry;
    private final ConnectionUrlBuilder urlBuilder;

    @GetMapping
    public Result<List<DataSourceInfo>> list() {
        return Result.ok(dataSourceManager.findAll());
    }

    @GetMapping("/{id}")
    public Result<DataSourceInfo> getById(@PathVariable Long id) {
        return dataSourceManager.findById(id)
                .map(Result::ok)
                .orElseGet(() -> Result.fail(404, "Data source not found: " + id));
    }

    @PostMapping
    public Result<DataSourceInfo> create(@RequestBody ConnectionRequest req) {
        try {
            DataSourceInfo ds = mapToEntity(req);
            ds.setId(null);
            DataSourceInfo created = dataSourceManager.create(ds);
            return Result.ok(created);
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<DataSourceInfo> update(@PathVariable Long id, @RequestBody ConnectionRequest req) {
        try {
            DataSourceInfo ds = mapToEntity(req);
            ds.setId(id);
            DataSourceInfo updated = dataSourceManager.update(ds);
            return Result.ok(updated);
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            dataSourceManager.delete(id);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    /**
     * Test a database connection.
     * Returns success/failure with timing and metadata.
     */
    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> testConnection(@PathVariable Long id) {
        return dataSourceManager.findById(id)
                .map(ds -> Result.ok(dataSourceManager.testConnection(ds)))
                .orElseGet(() -> Result.fail(404, "Data source not found: " + id));
    }

    /**
     * Test a connection without saving (inline test from request body).
     */
    @PostMapping("/test")
    public Result<Map<String, Object>> testInline(@RequestBody ConnectionRequest req) {
        DataSourceInfo ds = mapToEntity(req);
        return Result.ok(dataSourceManager.testConnection(ds));
    }

    /**
     * List all supported database types with metadata (ports, URL templates, etc.)
     */
    @GetMapping("/types")
    public Result<List<DbTypeMeta>> listTypes() {
        return Result.ok(dbTypeRegistry.getAllTypes());
    }

    /**
     * Get default connection parameters for a db_type (port, user, URL template).
     */
    @GetMapping("/defaults/{dbType}")
    public Result<Map<String, Object>> getDefaults(@PathVariable String dbType) {
        DbTypeMeta meta = dbTypeRegistry.getDbMeta(dbType).orElse(null);
        if (meta == null) {
            return Result.fail(404, "Unknown db_type: " + dbType);
        }
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("dbType", meta.getDbType());
        defaults.put("label", meta.getLabel());
        defaults.put("port", meta.getPort());
        defaults.put("user", meta.getUser());
        defaults.put("defaultDatabase", meta.getDefaultDatabase());
        defaults.put("urlTemplate", meta.getUrlTemplate());
        defaults.put("driverClassHint", meta.getDriverClassHint());
        defaults.put("emoji", meta.getEmoji());
        defaults.put("description", meta.getDescription());
        return Result.ok(defaults);
    }

    private DataSourceInfo mapToEntity(ConnectionRequest req) {
        int port = req.getPort() != null && req.getPort() > 0 ? req.getPort() :
                urlBuilder.getDefaultPort(req.getDbType());

        return DataSourceInfo.builder()
                .name(req.getName())
                .dbType(req.getDbType())
                .host(req.getHost())
                .port(port)
                .username(req.getUsername())
                .passwordEnc(req.getPassword())
                .databaseName(req.getDatabaseName())
                .sid(req.getSid())
                .serviceName(req.getServiceName())
                .extraParams(req.getExtraParams())
                .driverVersion(req.getDriverVersion())
                .poolSize(req.getPoolSize())
                .note(req.getNote())
                .build();
    }
}
