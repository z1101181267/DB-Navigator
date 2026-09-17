package com.dbnav.controller;

import com.dbnav.common.Result;
import com.dbnav.driver.*;
import com.dbnav.driver.model.DbTypeMeta;
import com.dbnav.driver.model.DriverInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST API for JDBC driver management.
 *
 * Endpoints aligned with RaccoonX's driver management UI/API:
 *   GET    /api/drivers/types              — list all supported db types
 *   GET    /api/drivers                    — list all registered drivers
 *   GET    /api/drivers/{dbType}           — list drivers for a specific db_type
 *   POST   /api/drivers/upload             — upload and register a JAR
 *   DELETE /api/drivers/{id}              — delete a driver
 *   PUT    /api/drivers/{id}/activate      — activate a driver version
 *   POST   /api/drivers/scan               — manually trigger directory scan
 *   GET    /api/drivers/active/{dbType}   — get active driver for a db_type
 */
@RestController
@RequestMapping("/api/drivers")
@RequiredArgsConstructor
public class DriverController {

    private final DriverRegistry driverRegistry;
    private final DriverDirectoryScanner directoryScanner;
    private final DbTypeRegistry dbTypeRegistry;
    private final DriverPathResolver pathResolver;

    /**
     * List all supported database types (from db-types.json).
     */
    @GetMapping("/types")
    public Result<List<DbTypeMeta>> listDbTypes() {
        return Result.ok(dbTypeRegistry.getAllTypes());
    }

    /**
     * List all registered drivers, optionally filtered by db_type.
     */
    @GetMapping
    public Result<List<DriverInfo>> listDrivers(
            @RequestParam(value = "dbType", required = false) String dbType) {
        return Result.ok(driverRegistry.listDrivers(dbType));
    }

    /**
     * Get the active driver for a specific db_type.
     */
    @GetMapping("/active/{dbType}")
    public Result<DriverInfo> getActiveDriver(@PathVariable String dbType) {
        return driverRegistry.getActiveDriver(dbType)
                .map(Result::ok)
                .orElseGet(() -> Result.fail(404, "No active driver for " + dbType));
    }

    /**
     * Upload and register a new JDBC driver JAR.
     *
     * The JAR will be stored at: drivers/<db_type>/<version>/<jar_filename>
     * If no active driver exists for this db_type, the new one will be auto-activated.
     */
    @PostMapping("/upload")
    public Result<DriverInfo> uploadDriver(
            @RequestParam("file") MultipartFile file,
            @RequestParam("dbType") String dbType,
            @RequestParam("version") String version,
            @RequestParam(value = "driverClass", required = false) String driverClass,
            @RequestParam(value = "note", required = false) String note) throws IOException {

        if (file.isEmpty()) {
            return Result.fail(400, "File is empty");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".jar")) {
            return Result.fail(400, "File must be a .jar file");
        }

        // Use driver_class_hint from db-types.json if not provided
        if (driverClass == null || driverClass.isEmpty()) {
            driverClass = dbTypeRegistry.getDriverClassHint(dbType);
        }

        // Stage the upload under a fixed, safe name. The client-supplied filename
        // is passed separately to addDriver() and validated there BEFORE it is
        // ever used to build a path (prevents path traversal).
        Path tempDir = Files.createTempDirectory("dbnav-upload-");
        Path tempFile = tempDir.resolve("upload.jar");
        file.transferTo(tempFile.toFile());

        try {
            DriverInfo driver = driverRegistry.addDriver(
                    dbType, version, driverClass,
                    tempFile.toFile(), filename, note);
            return Result.ok(driver);
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        } finally {
            // addDriver() moves the file away on success; these are no-ops then.
            try { Files.deleteIfExists(tempFile); } catch (Exception ignored) { }
            try { Files.deleteIfExists(tempDir); } catch (Exception ignored) { }
        }
    }

    /**
     * Delete a driver by ID.
     * If the deleted driver was active, the next available driver is auto-activated.
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteDriver(@PathVariable Long id) {
        try {
            driverRegistry.deleteDriver(id);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    /**
     * Activate a specific driver version (deactivates all others for the same db_type).
     */
    @PutMapping("/{id}/activate")
    public Result<Void> activateDriver(@PathVariable Long id) {
        try {
            driverRegistry.activateDriver(id);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    /**
     * Manually trigger a scan of the drivers/ directory.
     */
    @PostMapping("/scan")
    public Result<Map<String, Object>> scanDriverDirectory() {
        int newCount = directoryScanner.scanAndRegister();
        return Result.ok(Map.of(
                "newDrivers", newCount,
                "totalDrivers", driverRegistry.listDrivers(null).size()
        ));
    }

    /**
     * Get the expected JAR directory path for a db_type/version.
     */
    @GetMapping("/path/{dbType}/{version}")
    public Result<Map<String, String>> getDriverPath(
            @PathVariable String dbType,
            @PathVariable String version) {
        File targetDir = pathResolver.getTargetDir(dbType, version);
        return Result.ok(Map.of(
                "directory", targetDir.getAbsolutePath(),
                "dbType", dbType,
                "version", version
        ));
    }
}
