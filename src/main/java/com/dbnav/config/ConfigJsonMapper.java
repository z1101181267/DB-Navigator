package com.dbnav.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Shared ObjectMapper for parsing the snake_case configuration files
 * (drivers-seed.json, db-types.json).
 *
 * Deliberately NOT a Spring bean: declaring an ObjectMapper bean would
 * disable Spring Boot's Jackson auto-configuration (it is
 * {@code @ConditionalOnMissingBean}), breaking LocalDateTime serialization
 * in the REST layer. A standalone mapper keeps the two concerns separate.
 */
public final class ConfigJsonMapper {

    private static final ObjectMapper INSTANCE = create();

    private ConfigJsonMapper() {
    }

    public static ObjectMapper get() {
        return INSTANCE;
    }

    private static ObjectMapper create() {
        ObjectMapper mapper = new ObjectMapper();
        // snake_case keys: db_type, driver_class, jar_filename, url_template, ...
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
