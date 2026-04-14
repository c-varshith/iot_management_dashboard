package com.iot.dashboard.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.iot.dashboard.util.PasswordUtil;

/**
 * DatabaseSetup utility.
 *
 * Ensures that all required tables exist and seeds an initial admin
 * user if none is present. Called once at application startup before
 * the login screen is shown.
 *
 * This class is responsible for the programmatic equivalent of setup.sql.
 * It is idempotent — safe to call on every launch.
 */
public class DatabaseSetup {

    private static final Logger LOGGER = Logger.getLogger(DatabaseSetup.class.getName());

    private static final String DEFAULT_ADMIN_USER     = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";
    private static final String DEFAULT_ADMIN_FULLNAME = "System Administrator";

    /**
     * Runs all setup operations: creates tables and seeds initial data.
     *
     * @return true if setup completed successfully, false otherwise
    */
    public static boolean runSetup() {
        try {
            DatabaseManager db = DatabaseManager.getInstance();
            try (Connection conn = db.getConnection()) {
                createTables(conn);
                seedSensorTypes(conn);
                seedDevices(conn);
                seedAdminUser(conn);
                LOGGER.info("Database setup completed successfully.");
                return true;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Database setup failed. " +
                    "Check database.properties and ensure MySQL is running.", e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    private static void createTables(Connection conn) throws SQLException {
        String[] ddl = {
            // Devices table
            "CREATE TABLE IF NOT EXISTS devices (" +
            "  device_id   SMALLINT     NOT NULL AUTO_INCREMENT, " +
            "  device_name VARCHAR(100) NOT NULL, " +
            "  location    VARCHAR(100) NOT NULL DEFAULT 'Unknown', " +
            "  status      TINYINT      NOT NULL DEFAULT 1, " +
            "  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
            "  PRIMARY KEY (device_id) " +
            ") ENGINE=InnoDB",

            // Sensor types lookup table
            "CREATE TABLE IF NOT EXISTS sensor_types (" +
            "  type_id     TINYINT     NOT NULL AUTO_INCREMENT, " +
            "  measurement VARCHAR(50) NOT NULL, " +
            "  unit        VARCHAR(20) NOT NULL, " +
            "  PRIMARY KEY (type_id) " +
            ") ENGINE=InnoDB",

            // Main time-series fact table
            // TIMESTAMP (4 bytes) is preferred over DATETIME (8 bytes)
            // FLOAT     (4 bytes) is sufficient for sensor precision
            "CREATE TABLE IF NOT EXISTS sensor_data (" +
            "  reading_id BIGINT    NOT NULL AUTO_INCREMENT, " +
            "  device_id  SMALLINT  NOT NULL, " +
            "  type_id    TINYINT   NOT NULL, " +
            "  timestamp  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
            "  value      FLOAT     NOT NULL, " +
            "  PRIMARY KEY (reading_id), " +
            "  INDEX idx_device_type_time (device_id, type_id, timestamp), " +
            "  INDEX idx_timestamp        (timestamp), " +
            "  FOREIGN KEY (device_id) REFERENCES devices(device_id) ON DELETE CASCADE, " +
            "  FOREIGN KEY (type_id)   REFERENCES sensor_types(type_id) ON DELETE CASCADE " +
            ") ENGINE=InnoDB",

            // Admin credentials table
            "CREATE TABLE IF NOT EXISTS admin_users (" +
            "  user_id       INT          NOT NULL AUTO_INCREMENT, " +
            "  username      VARCHAR(50)  NOT NULL UNIQUE, " +
            "  password_hash VARCHAR(255) NOT NULL, " +
            "  full_name     VARCHAR(100) DEFAULT NULL, " +
            "  last_login    TIMESTAMP    NULL, " +
            "  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
            "  PRIMARY KEY (user_id) " +
            ") ENGINE=InnoDB"
        };

        try (Statement st = conn.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
            }
        }
        LOGGER.info("Tables created/verified.");
    }

    // ------------------------------------------------------------------
    private static void seedSensorTypes(Connection conn) throws SQLException {
        String check = "SELECT COUNT(*) FROM sensor_types";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(check)) {
            if (rs.next() && rs.getInt(1) > 0) return; // already seeded
        }

        String insert = "INSERT INTO sensor_types (type_id, measurement, unit) VALUES (?,?,?)";
        Object[][] types = {
            {1, "Temperature",  "°C"},
            {2, "Humidity",     "%"},
            {3, "Voltage",      "V"},
            {4, "Active Power", "W"}
        };
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            for (Object[] row : types) {
                ps.setInt   (1, (int)    row[0]);
                ps.setString(2, (String) row[1]);
                ps.setString(3, (String) row[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        LOGGER.info("Sensor types seeded.");
    }

    // ------------------------------------------------------------------
    private static void seedDevices(Connection conn) throws SQLException {
        String check = "SELECT COUNT(*) FROM devices";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(check)) {
            if (rs.next() && rs.getInt(1) > 0) return;
        }

        String insert = "INSERT INTO devices (device_name, location, status) VALUES (?,?,1)";
        String[][] devices = {
            {"Smart Meter A1",    "Building A - Floor 1"},
            {"Smart Meter B1",    "Building B - Floor 1"},
            {"Industrial Sensor", "Server Room"},
            {"Grid Monitor",      "Main Distribution"}
        };
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            for (String[] d : devices) {
                ps.setString(1, d[0]);
                ps.setString(2, d[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        LOGGER.info("Devices seeded.");
    }

    // ------------------------------------------------------------------
    private static void seedAdminUser(Connection conn) throws SQLException {
        String check = "SELECT COUNT(*) FROM admin_users";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(check)) {
            if (rs.next() && rs.getInt(1) > 0) return; // admin user already exists
        }

        // Hash the default password using BCrypt (10 rounds)
        String hash = PasswordUtil.hashPassword(DEFAULT_ADMIN_PASSWORD);

        String insert = "INSERT INTO admin_users (username, password_hash, full_name) VALUES (?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, DEFAULT_ADMIN_USER);
            ps.setString(2, hash);
            ps.setString(3, DEFAULT_ADMIN_FULLNAME);
            ps.executeUpdate();
        }
        LOGGER.info("Default admin user created. Login: admin / admin123");
    }
}
