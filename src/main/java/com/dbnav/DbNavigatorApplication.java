package com.dbnav;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * DB Navigator - Multi-database management platform.
 *
 * JDBC driver management aligned with RaccoonX (DBCheck) open-source project:
 *   - drivers/<db_type>/<version>/<jar> directory structure
 *   - Driver metadata registry in embedded DB (H2, equivalent of RaccoonX SQLite)
 *   - Seed configuration for out-of-box driver registration
 *   - Directory scanning with auto-registration
 *   - Path relocation for packaged deployment
 *   - Activation mechanism (one active driver per db_type)
 *
 * Managed databases: Oracle, MySQL, PostgreSQL, SQL Server, KingbaseES
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DbNavigatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbNavigatorApplication.class, args);
    }
}
