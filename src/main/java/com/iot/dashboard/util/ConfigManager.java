package com.iot.dashboard.util;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ConfigManager — Manages external application configuration.
 *
 * Configuration is stored in ~/.iot-dashboard/config.properties
 * Falls back to embedded defaults from classpath if file doesn't exist.
 *
 * Provides centralized getter/setter for all user-configurable values:
 *  - Database connection (URL, username, password)
 *  - Sensor mode (USE_REAL_SENSOR)
 *  - Serial port and baud rate
 */
public class ConfigManager {

    private static final Logger LOGGER = Logger.getLogger(ConfigManager.class.getName());
    private static final String DEFAULT_CONFIG_FILE = "config.properties";
    private static final String CONFIG_OVERRIDE_PROPERTY = "iot.dashboard.config";
    private static final String CONFIG_OVERRIDE_ENV = "IOT_DASHBOARD_CONFIG";

    private static ConfigManager instance;
    private final Properties properties;
    private final Path configPath;

    // =========================================================================
    // Singleton
    // =========================================================================

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    private ConfigManager() {
        this.properties = new Properties();
        this.configPath = resolveConfigPath();

        loadConfig();
    }

    // =========================================================================
    // Load Configuration
    // =========================================================================

    private void loadConfig() {
        // First, load embedded defaults from classpath
        loadEmbeddedDefaults();

        // Then, override with external file if it exists
        if (Files.exists(configPath)) {
            try (InputStream input = Files.newInputStream(configPath)) {
                properties.load(input);
                LOGGER.info("Configuration loaded from: " + configPath);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to load config file: " + configPath, e);
            }
        } else {
            LOGGER.info("No external config found at: " + configPath + " — using embedded defaults.");
        }

        // Finally, apply runtime overrides from environment variables / JVM properties.
        applyRuntimeOverrides();
    }

    private void loadEmbeddedDefaults() {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("database.properties")) {
            if (input != null) {
                properties.load(input);
                LOGGER.info("Loaded embedded defaults from database.properties");
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load embedded defaults", e);
        }
    }

    private Path resolveConfigPath() {
        String override = firstNonBlank(
                System.getProperty(CONFIG_OVERRIDE_PROPERTY),
                System.getenv(CONFIG_OVERRIDE_ENV)
        );

        if (override != null) {
            return Paths.get(override).toAbsolutePath().normalize();
        }

        return Paths.get(
                System.getProperty("user.home"),
                ".iot-dashboard",
                DEFAULT_CONFIG_FILE
        );
    }

    private void applyRuntimeOverrides() {
        applyOverride("db.url", "DB_URL", "iot.dashboard.db.url");
        applyOverride("db.username", "DB_USERNAME", "iot.dashboard.db.username");
        applyOverride("db.password", "DB_PASSWORD", "iot.dashboard.db.password");
        applyOverride("use.real.sensor", "USE_REAL_SENSOR", "iot.dashboard.use.real.sensor");
        applyOverride("serial.port", "SERIAL_PORT", "iot.dashboard.serial.port");
        applyOverride("baud.rate", "BAUD_RATE", "iot.dashboard.baud.rate");
        applyOverride("dht.type", "DHT_TYPE", "iot.dashboard.dht.type");
        applyOverride("hikari.maximumPoolSize", "HIKARI_MAX_POOL_SIZE", "iot.dashboard.hikari.maximumPoolSize");
        applyOverride("hikari.minimumIdle", "HIKARI_MIN_IDLE", "iot.dashboard.hikari.minimumIdle");
    }

    private void applyOverride(String propertyKey, String envKey, String systemKey) {
        String value = firstNonBlank(System.getProperty(systemKey), System.getenv(envKey));
        if (value != null) {
            properties.setProperty(propertyKey, value);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    // =========================================================================
    // First-Run Helpers
    // =========================================================================

    /**
     * Returns true if an external config file already exists on disk.
     * Used by DashboardApplication to decide whether to show the first-run wizard.
     */
    public boolean isConfigFilePresent() {
        return Files.exists(configPath);
    }

    /**
     * Creates the config directory and writes a starter config.properties
     * if one does not already exist. Safe to call on every launch — no-op
     * if the file is already present.
     *
     * This guarantees users can always find the file at the documented path
     * even before they open Settings for the first time.
     */
    public synchronized void createConfigIfMissing() throws IOException {
        if (Files.exists(configPath)) return;

        Files.createDirectories(configPath.getParent());

        String template =
            "# IoT Dashboard — External Configuration\n" +
            "# Edit this file to set your MySQL credentials, then restart the app.\n" +
            "#\n" +
            "# Windows path: %USERPROFILE%\\.iot-dashboard\\config.properties\n" +
            "# Linux path:   ~/.iot-dashboard/config.properties\n" +
            "#\n" +
            "# ---------------------------------------------------------------\n" +
            "db.url=jdbc:mysql://localhost:3306/iot_dashboard" +
                "?useSSL=false&allowPublicKeyRetrieval=true" +
                "&serverTimezone=UTC&rewriteBatchedStatements=true\n" +
            "db.username=root\n" +
            "db.password=\n" +
            "\n" +
            "# Sensor mode (false = full simulation, true = real Arduino sensor)\n" +
            "use.real.sensor=false\n" +
            "serial.port=/dev/ttyUSB0\n" +
            "baud.rate=9600\n" +
            "dht.type=DHT22\n";

        try (OutputStream out = Files.newOutputStream(configPath)) {
            out.write(template.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        LOGGER.info("Created starter config at: " + configPath);
    }

    // =========================================================================
    // Save Configuration
    // =========================================================================

    public synchronized void saveConfig() throws IOException {
        // Ensure directory exists
        Files.createDirectories(configPath.getParent());

        try (OutputStream output = Files.newOutputStream(configPath)) {
            properties.store(output, "IoT Dashboard Configuration\nAuto-generated — do not edit manually");
            LOGGER.info("Configuration saved to: " + configPath);
        }
    }

    // =========================================================================
    // Database Configuration
    // =========================================================================

    public String getDatabaseUrl() {
        return properties.getProperty("db.url", "jdbc:mysql://localhost:3306/iot_dashboard?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true");
    }

    public void setDatabaseUrl(String url) {
        properties.setProperty("db.url", url);
    }

    public String getDatabaseUsername() {
        return properties.getProperty("db.username", "root");
    }

    public void setDatabaseUsername(String username) {
        properties.setProperty("db.username", username);
    }

    public String getDatabasePassword() {
        return properties.getProperty("db.password", "");
    }

    public void setDatabasePassword(String password) {
        properties.setProperty("db.password", password);
    }

    // =========================================================================
    // Sensor Configuration
    // =========================================================================

    public boolean isUseRealSensor() {
        String value = properties.getProperty("use.real.sensor", "false");
        return Boolean.parseBoolean(value);
    }

    public void setUseRealSensor(boolean useRealSensor) {
        properties.setProperty("use.real.sensor", String.valueOf(useRealSensor));
    }

    public String getSerialPort() {
        return properties.getProperty("serial.port", "/dev/ttyUSB0");
    }

    public void setSerialPort(String port) {
        properties.setProperty("serial.port", port);
    }

    public int getBaudRate() {
        String value = properties.getProperty("baud.rate", "9600");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING, "Invalid baud rate: " + value + " — using default 9600", e);
            return 9600;
        }
    }

    public void setBaudRate(int baudRate) {
        properties.setProperty("baud.rate", String.valueOf(baudRate));
    }

    /**
     * DHT sensor type — "DHT11" or "DHT22".
     * Defaults to DHT22 (higher accuracy, wider range).
     */
    public String getDhtType() {
        return properties.getProperty("dht.type", "DHT22");
    }

    public void setDhtType(String dhtType) {
        if (!"DHT11".equals(dhtType) && !"DHT22".equals(dhtType)) {
            throw new IllegalArgumentException("dht.type must be DHT11 or DHT22, got: " + dhtType);
        }
        properties.setProperty("dht.type", dhtType);
    }

    // =========================================================================
    // HikariCP Configuration (optional)
    // =========================================================================

    public int getHikariMaxPoolSize() {
        String value = properties.getProperty("hikari.maximumPoolSize", "10");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    public int getHikariMinIdle() {
        String value = properties.getProperty("hikari.minimumIdle", "2");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 2;
        }
    }


    // =========================================================================
    // UI Theme
    // =========================================================================

    public boolean isLightTheme() {
        return "light".equalsIgnoreCase(properties.getProperty("ui.theme", "dark"));
    }

    public void setLightTheme(boolean light) {
        properties.setProperty("ui.theme", light ? "light" : "dark");
    }

    // =========================================================================
    // Utility
    // =========================================================================

    public Path getConfigPath() {
        return configPath;
    }

    public synchronized Properties snapshot() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    public synchronized void restore(Properties snapshot) {
        properties.clear();
        if (snapshot != null) {
            properties.putAll(snapshot);
        }
    }

    public synchronized void reload() {
        properties.clear();
        loadConfig();
    }
}
