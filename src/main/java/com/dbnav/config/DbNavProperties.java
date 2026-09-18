package com.dbnav.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Custom configuration properties for DB Navigator.
 * Bound from application.yml under the 'dbnav' prefix.
 */
@Data
@ConfigurationProperties(prefix = "dbnav")
public class DbNavProperties {

    /** Root directory for JDBC driver JARs (drivers/<db_type>/<version>/<jar>) */
    private String driversDir = "./drivers";

    /** Data directory for embedded DB and config files */
    private String dataDir = "./data";

    /** Connection pool (HikariCP) defaults */
    private Pool pool = new Pool();

    /** Driver seed JSON resource path */
    private String driversSeed = "classpath:drivers-seed.json";

    /** Database types JSON resource path */
    private String dbTypesConfig = "classpath:db-types.json";

    /** 巡检模板种子（默认章节与查询） */
    private String inspectionTemplates = "classpath:inspection/templates.json";

    /** 配置基线种子（内置默认阈值） */
    private String inspectionBaselines = "classpath:inspection/baselines.json";

    @Data
    public static class Pool {
        private int maximumPoolSize = 10;
        private int minimumIdle = 2;
        private int connectionTimeout = 30000;
        private int idleTimeout = 600000;
        private int maxLifetime = 1800000;
    }
}
