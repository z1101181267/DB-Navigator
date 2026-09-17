package com.dbnav.driver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamically loads JDBC drivers from JAR files using URLClassLoader.
 *
 * Aligned with RaccoonX's JPype-based approach (which uses JVM classpath
 * to load JDBC drivers). In pure Java, we use URLClassLoader to achieve
 * the same result: load any JAR at runtime without pre-configuring classpath.
 *
 * Key design decisions (matching RaccoonX patterns):
 *   - One URLClassLoader per (dbType + version) combination
 *   - ClassLoaders are cached in a ConcurrentHashMap for reuse
 *   - Driver instances are also cached to avoid repeated Class.forName()
 *   - Thread-context ClassLoader is NOT modified (isolation per db_type)
 */
@Slf4j
@Component
public class DynamicDriverLoader {

    /**
     * Cache: (dbType + "::" + version) -> URLClassLoader
     */
    private final Map<String, URLClassLoader> classLoaderCache = new ConcurrentHashMap<>();

    /**
     * Cache: (dbType + "::" + version) -> Driver instance
     */
    private final Map<String, Driver> driverCache = new ConcurrentHashMap<>();

    /**
     * Load and cache a JDBC Driver from the given JAR file.
     *
     * @param dbType       database type key (e.g. "oracle")
     * @param version      driver version (e.g. "8")
     * @param jarFile      the JAR file to load
     * @param driverClass  fully-qualified driver class name
     * @return loaded Driver instance
     */
    public Driver loadDriver(String dbType, String version, File jarFile, String driverClass) {
        String cacheKey = dbType + "::" + version;

        // Check driver cache first
        Driver cachedDriver = driverCache.get(cacheKey);
        if (cachedDriver != null) {
            return cachedDriver;
        }

        // Check classloader cache
        URLClassLoader classLoader = classLoaderCache.get(cacheKey);
        if (classLoader == null) {
            try {
                URL jarUrl = jarFile.toURI().toURL();
                // Parent = platform class loader. This is critical on Java 9+:
                // java.sql.Driver lives in the java.sql platform module, which the
                // bootstrap loader (parent=null) cannot see. The platform loader
                // exposes all JDK modules while still isolating the driver JAR
                // from application classes.
                classLoader = new URLClassLoader(new URL[]{jarUrl},
                        ClassLoader.getPlatformClassLoader());
                classLoaderCache.put(cacheKey, classLoader);
                log.info("Created URLClassLoader for {} v{} from {}", dbType, version, jarFile.getAbsolutePath());
            } catch (MalformedURLException e) {
                throw new RuntimeException("Invalid JAR URL: " + jarFile, e);
            }
        }

        // Load the driver class
        try {
            Class<?> driverClazz = Class.forName(driverClass, true, classLoader);
            Driver driver = (Driver) driverClazz.getDeclaredConstructor().newInstance();
            driverCache.put(cacheKey, driver);
            log.info("Loaded JDBC driver: {} v{} -> {} from {}",
                    dbType, version, driverClass, jarFile.getName());
            return driver;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Driver class not found: " + driverClass +
                    " in JAR: " + jarFile.getName() + ". Ensure the JAR is the correct version.", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate driver: " + driverClass, e);
        }
    }

    /**
     * 从**应用自身 classpath** 加载驱动，而不是从 drivers/ 目录的 JAR。
     *
     * 用于随应用一起分发的驱动（目前是内置自检用的 H2——它本来就是本项目的
     * runtime 依赖）。这类驱动不需要用户手动放置 JAR，因此走应用类加载器。
     *
     * 与 {@link #loadDriver} 分开缓存，避免与同名但来自不同 JAR 的驱动互相污染。
     */
    public Driver loadBundledDriver(String dbType, String version, String driverClass) {
        String cacheKey = dbType + "::" + version;
        Driver cached = driverCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            ClassLoader appLoader = getClass().getClassLoader();
            Class<?> clazz = Class.forName(driverClass, true, appLoader);
            Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
            driverCache.put(cacheKey, driver);
            log.info("Loaded bundled JDBC driver: {} v{} -> {} (from application classpath)",
                    dbType, version, driverClass);
            return driver;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Bundled driver class not found on classpath: " + driverClass, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate bundled driver: " + driverClass, e);
        }
    }

    /**
     * 判断某个驱动类是否已在应用 classpath 上（无需外部 JAR）。
     */
    public boolean isAvailableOnClasspath(String driverClass) {
        if (driverClass == null || driverClass.isBlank()) {
            return false;
        }
        try {
            Class.forName(driverClass, false, getClass().getClassLoader());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Create a JDBC connection using a dynamically loaded driver.
     *
     * @param dbType       database type
     * @param version      driver version
     * @param jarFile      JAR file path
     * @param driverClass  driver class name
     * @param url          JDBC connection URL
     * @param username     database username
     * @param password     database password
     * @return live JDBC Connection
     */
    public Connection connect(String dbType, String version, File jarFile,
                              String driverClass, String url,
                              String username, String password) throws Exception {
        Driver driver = loadDriver(dbType, version, jarFile, driverClass);
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        return driver.connect(url, props);
    }

    /**
     * 用应用 classpath 上的驱动建立连接（配合 {@link #loadBundledDriver}）。
     */
    public Connection connectBundled(String dbType, String version, String driverClass,
                                     String url, String username, String password) throws Exception {
        Driver driver = loadBundledDriver(dbType, version, driverClass);
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        return driver.connect(url, props);
    }

    /**
     * Evict a cached classloader and driver (e.g. after driver deletion or re-activation).
     */
    public void evict(String dbType, String version) {
        String cacheKey = dbType + "::" + version;
        driverCache.remove(cacheKey);
        URLClassLoader cl = classLoaderCache.remove(cacheKey);
        if (cl != null) {
            try {
                cl.close();
            } catch (Exception e) {
                log.warn("Failed to close classloader for {} v{}", dbType, version, e);
            }
        }
    }

    /**
     * Evict all cached entries for a db_type.
     */
    public void evictByDbType(String dbType) {
        String prefix = dbType + "::";
        classLoaderCache.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .forEach(k -> {
                    driverCache.remove(k);
                    URLClassLoader cl = classLoaderCache.remove(k);
                    if (cl != null) {
                        try { cl.close(); } catch (Exception ignored) {}
                    }
                });
    }

    /**
     * Check if a driver is already loaded and cached.
     */
    public boolean isLoaded(String dbType, String version) {
        return driverCache.containsKey(dbType + "::" + version);
    }
}
