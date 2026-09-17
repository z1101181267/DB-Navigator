package com.dbnav.driver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs on application startup to initialize the driver registry.
 *
 * Aligned with RaccoonX's startup sequence:
 *   1. init_db() (done by schema.sql)
 *   2. seed_driver_registry() — import seed JSON if table empty
 *   3. scan_driver_dirs() — scan disk for JARs not yet in registry
 *
 * Order matters: seed first (sets metadata), then scan (verifies JARs on disk).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriverStartupInitializer {

    private final DriverSeedLoader seedLoader;
    private final DriverDirectoryScanner directoryScanner;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("=== DB Navigator driver registry initialization ===");

        // Step 1: Load seed metadata if registry is empty
        seedLoader.loadSeedIfEmpty();

        // Step 2: Scan drivers/ directory for any JARs not yet registered
        int newFromScan = directoryScanner.scanAndRegister();

        log.info("=== Driver registry initialization complete " +
                "(scan added {} new entries) ===", newFromScan);
    }
}
