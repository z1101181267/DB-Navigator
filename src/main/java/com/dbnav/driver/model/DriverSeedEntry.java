package com.dbnav.driver.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Single entry in the drivers-seed.json file.
 *
 * Aligned with RaccoonX's drivers_seed.json format:
 *   db_type, version, driver_class, jar_filename, file_size, is_active, note
 *
 * Note: jar_path is deliberately absent (resolved at import time by filename).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DriverSeedEntry {

    private String dbType;
    private String version;
    private String driverClass;
    private String jarFilename;
    private Long fileSize;
    private Integer isActive;
    private String note;
}
