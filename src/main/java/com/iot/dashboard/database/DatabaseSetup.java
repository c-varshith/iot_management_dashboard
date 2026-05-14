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
 * Ensures that all required tables exist, creates the trigger and stored
 * procedure, and seeds an initial admin user if none is present.
 * Called once at application startup before the login screen is shown.
 *
 * This class is responsible for the programmatic equivalent of setup.sql.
 * It is idempotent — safe to call on every launch.
 *
 * Tables created:
 *   1. devices      — edge device metadata
 *   2. sensor_types — sensor type lookup
 *   3. sensor_data  — time-series readings (main fact table)
 *   4. admin_users  — hashed admin credentials
 *   5. alerts       — threshold-violation alerts (auto-populated by trigger)
 *
 * Advanced DB features:
 *   - Trigger : trg_high_sensor_alert (AFTER INSERT on sensor_data)
 *   - Stored Procedure : GetSensorStats(type_id, hours)
 */
public class DatabaseSetup {

    private static final Logger LOGGER = Logger.getLogger(DatabaseSetup.class.getName());

    private static final String DEFAULT_ADMIN_USER     = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";
    private static final String DEFAULT_ADMIN_FULLNAME = "System Administrator";

    /**
     * Runs all setup operations: creates tables, trigger, procedure, seeds data.
     *
     * @return true if setup completed successfully, false otherwise
     */
    public static boolean runSetup() {
        try {
            DatabaseManager db = DatabaseManager.getInstance();
            try (Connection conn = db.getConnection()) {
                createTables(conn);
                alterTables(conn);           // DDL: ALTER TABLE (ip_address column)
                addCheckConstraints(conn);   // Assertions via CHECK constraints
                createView(conn);            // Advanced SQL: CREATE VIEW
                createTrigger(conn);
                createStoredProcedure(conn);
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
    // Table DDL
    // ------------------------------------------------------------------
    private static void createTables(Connection conn) throws SQLException {
        String[] ddl = {
            // TABLE 1: Devices
            "CREATE TABLE IF NOT EXISTS devices (" +
            "  device_id   SMALLINT     NOT NULL AUTO_INCREMENT, " +
            "  device_name VARCHAR(100) NOT NULL, " +
            "  location    VARCHAR(100) NOT NULL DEFAULT 'Unknown', " +
            "  status      TINYINT      NOT NULL DEFAULT 1, " +
            "  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
            "  PRIMARY KEY (device_id) " +
            ") ENGINE=InnoDB",

            // TABLE 2: Sensor types lookup
            "CREATE TABLE IF NOT EXISTS sensor_types (" +
            "  type_id     TINYINT     NOT NULL AUTO_INCREMENT, " +
            "  measurement VARCHAR(50) NOT NULL, " +
            "  unit        VARCHAR(20) NOT NULL, " +
            "  PRIMARY KEY (type_id) " +
            ") ENGINE=InnoDB",

            // TABLE 3: Main time-series fact table
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

            // TABLE 4: Admin credentials
            "CREATE TABLE IF NOT EXISTS admin_users (" +
            "  user_id       INT          NOT NULL AUTO_INCREMENT, " +
            "  username      VARCHAR(50)  NOT NULL UNIQUE, " +
            "  password_hash VARCHAR(255) NOT NULL, " +
            "  full_name     VARCHAR(100) DEFAULT NULL, " +
            "  last_login    TIMESTAMP    NULL, " +
            "  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
            "  PRIMARY KEY (user_id) " +
            ") ENGINE=InnoDB",

            // TABLE 5: Alerts — auto-populated by trg_high_sensor_alert trigger
            "CREATE TABLE IF NOT EXISTS alerts (" +
            "  alert_id      INT          NOT NULL AUTO_INCREMENT, " +
            "  device_id     SMALLINT     NOT NULL, " +
            "  type_id       TINYINT      NOT NULL, " +
            "  alert_message VARCHAR(255) NOT NULL, " +
            "  threshold_val FLOAT        NOT NULL, " +
            "  actual_val    FLOAT        NOT NULL, " +
            "  is_resolved   TINYINT      NOT NULL DEFAULT 0, " +
            "  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
            "  PRIMARY KEY (alert_id), " +
            "  INDEX idx_alert_device   (device_id), " +
            "  INDEX idx_alert_resolved (is_resolved), " +
            "  FOREIGN KEY (device_id) REFERENCES devices(device_id) ON DELETE CASCADE, " +
            "  FOREIGN KEY (type_id)   REFERENCES sensor_types(type_id) ON DELETE CASCADE " +
            ") ENGINE=InnoDB"
        };

        try (Statement st = conn.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
            }
        }
        LOGGER.info("Tables (5) created/verified.");
    }

    // ------------------------------------------------------------------
    // DDL: ALTER TABLE
    // Adds the ip_address column to the devices table after initial creation.
    // Uses IF NOT EXISTS guard (MySQL 8.0+) to keep this idempotent.
    // Demonstrates the ALTER DDL command required by Chapter 6.
    // ------------------------------------------------------------------
    private static void alterTables(Connection conn) throws SQLException {
        // Check if the column already exists before attempting to add it
        String checkCol =
            "SELECT COUNT(*) FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA = DATABASE() " +
            "  AND TABLE_NAME   = 'devices' " +
            "  AND COLUMN_NAME  = 'ip_address'";

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(checkCol)) {
            if (rs.next() && rs.getInt(1) > 0) {
                LOGGER.info("ALTER TABLE: ip_address column already exists — skipping.");
                return;
            }
        }

        String alterSql =
            "ALTER TABLE devices " +
            "ADD COLUMN ip_address VARCHAR(45) DEFAULT NULL " +
            "COMMENT 'Optional IP address of the edge device'";

        try (Statement st = conn.createStatement()) {
            st.execute(alterSql);
        }
        LOGGER.info("ALTER TABLE: ip_address column added to devices.");
    }

    // ------------------------------------------------------------------
    // Assertions via CHECK Constraints
    // MySQL 8.0.16+ enforces CHECK constraints as assertion-style rules.
    // SQL standard ASSERT is not supported in MySQL; CHECK constraints
    // serve the same data-integrity purpose and are shown here as the
    // equivalent (noted in Chapter 6 of the project report).
    // ------------------------------------------------------------------
    private static void addCheckConstraints(Connection conn) throws SQLException {
        String[] constraints = {
            // Check if chk_device_status already exists
            "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS " +
            "WHERE CONSTRAINT_SCHEMA = DATABASE() " +
            "  AND TABLE_NAME        = 'devices' " +
            "  AND CONSTRAINT_NAME   = 'chk_device_status'",
            "ALTER TABLE devices ADD CONSTRAINT chk_device_status CHECK (status IN (0,1))",

            "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS " +
            "WHERE CONSTRAINT_SCHEMA = DATABASE() " +
            "  AND TABLE_NAME        = 'alerts' " +
            "  AND CONSTRAINT_NAME   = 'chk_alert_resolved'",
            "ALTER TABLE alerts ADD CONSTRAINT chk_alert_resolved CHECK (is_resolved IN (0,1))"
        };

        try (Statement st = conn.createStatement()) {
            // Process pairs: [check query, alter statement]
            for (int i = 0; i < constraints.length; i += 2) {
                try (ResultSet rs = st.executeQuery(constraints[i])) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        st.execute(constraints[i + 1]);
                        LOGGER.info("CHECK constraint added: " + constraints[i + 1]);
                    } else {
                        LOGGER.info("CHECK constraint already exists — skipping.");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Advanced SQL: CREATE VIEW — vw_active_alerts
    // Encapsulates the three-table JOIN so application code can query
    // a single view instead of repeating the JOIN every time.
    // Demonstrates: CREATE VIEW (Advanced SQL — Chapter 6)
    // ------------------------------------------------------------------
    private static void createView(Connection conn) throws SQLException {
        // DROP first (idempotent — CREATE OR REPLACE works for views in MySQL)
        String viewSql =
            "CREATE OR REPLACE VIEW vw_active_alerts AS " +
            "SELECT " +
            "    a.alert_id, " +
            "    d.device_name, " +
            "    d.location, " +
            "    st.measurement, " +
            "    st.unit, " +
            "    a.alert_message, " +
            "    a.actual_val, " +
            "    a.threshold_val, " +
            "    a.created_at " +
            "FROM   alerts       a " +
            "JOIN   devices      d  ON a.device_id = d.device_id " +
            "JOIN   sensor_types st ON a.type_id   = st.type_id " +
            "WHERE  a.is_resolved = 0 " +
            "ORDER  BY a.created_at DESC";

        try (Statement st = conn.createStatement()) {
            st.execute(viewSql);
        }
        LOGGER.info("View vw_active_alerts created/replaced.");
    }

    // ------------------------------------------------------------------
    // Trigger: trg_high_sensor_alert
    // Fires AFTER INSERT on sensor_data.
    // If the new reading exceeds the threshold for its sensor type,
    // an alert row is automatically inserted into the alerts table.
    // Thresholds: Temp > 45°C | Humidity > 90% | Voltage > 250V | Power > 3500W
    // ------------------------------------------------------------------
    private static void createTrigger(Connection conn) throws SQLException {
        // Drop existing trigger first (idempotent)
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TRIGGER IF EXISTS trg_high_sensor_alert");
        }

        String triggerSql =
            "CREATE TRIGGER trg_high_sensor_alert " +
            "AFTER INSERT ON sensor_data " +
            "FOR EACH ROW " +
            "BEGIN " +
            "    DECLARE v_threshold FLOAT DEFAULT NULL; " +
            "    SET v_threshold = CASE NEW.type_id " +
            "        WHEN 1 THEN 45.0 " +    // Temperature (°C)
            "        WHEN 2 THEN 90.0 " +    // Humidity (%)
            "        WHEN 3 THEN 250.0 " +   // Voltage (V)
            "        WHEN 4 THEN 3500.0 " +  // Active Power (W)
            "        ELSE NULL " +
            "    END; " +
            "    IF v_threshold IS NOT NULL AND NEW.value > v_threshold THEN " +
            "        INSERT INTO alerts " +
            "            (device_id, type_id, alert_message, threshold_val, actual_val) " +
            "        VALUES ( " +
            "            NEW.device_id, " +
            "            NEW.type_id, " +
            "            CONCAT('ALERT: Sensor type ', NEW.type_id, " +
            "                   ' on device ', NEW.device_id, " +
            "                   ' — value=', ROUND(NEW.value,2), " +
            "                   ' exceeds threshold=', v_threshold), " +
            "            v_threshold, " +
            "            NEW.value " +
            "        ); " +
            "    END IF; " +
            "END";

        try (Statement st = conn.createStatement()) {
            st.execute(triggerSql);
        }
        LOGGER.info("Trigger trg_high_sensor_alert created/replaced.");
    }

    // ------------------------------------------------------------------
    // Stored Procedure: GetSensorStats
    // Returns aggregated stats (count, avg, min, max, stddev) for a
    // given sensor type over the last N hours.
    // Usage: CALL GetSensorStats(1, 24);
    // ------------------------------------------------------------------
    private static void createStoredProcedure(Connection conn) throws SQLException {
        // Drop existing procedure (idempotent)
        try (Statement st = conn.createStatement()) {
            st.execute("DROP PROCEDURE IF EXISTS GetSensorStats");
        }

        String procSql =
            "CREATE PROCEDURE GetSensorStats( " +
            "    IN p_type_id TINYINT, " +
            "    IN p_hours   INT " +
            ") " +
            "BEGIN " +
            "    SELECT " +
            "        st.measurement                 AS sensor_type, " +
            "        st.unit                        AS unit, " +
            "        COUNT(sd.reading_id)           AS total_readings, " +
            "        ROUND(AVG(sd.value),   2)      AS avg_value, " +
            "        ROUND(MIN(sd.value),   2)      AS min_value, " +
            "        ROUND(MAX(sd.value),   2)      AS max_value, " +
            "        ROUND(STDDEV(sd.value),2)      AS std_dev, " +
            "        MAX(sd.timestamp)              AS last_reading_at " +
            "    FROM sensor_data  sd " +
            "    JOIN sensor_types st ON sd.type_id = st.type_id " +
            "    WHERE sd.type_id  = p_type_id " +
            "      AND sd.timestamp >= NOW() - INTERVAL p_hours HOUR; " +
            "END";

        try (Statement st = conn.createStatement()) {
            st.execute(procSql);
        }
        LOGGER.info("Stored procedure GetSensorStats created/replaced.");
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
            if (rs.next() && rs.getInt(1) > 0) return;
        }

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
