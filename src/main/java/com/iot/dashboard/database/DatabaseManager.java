package com.iot.dashboard.database;

import com.iot.dashboard.model.AdminUser;
import com.iot.dashboard.model.SensorData;
import com.iot.dashboard.model.SensorType;
import com.iot.dashboard.util.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DatabaseManager — Singleton data access layer.
 *
 * Design decisions:
 *  - Singleton pattern: guarantees one pool instance per JVM lifecycle.
 *  - HikariCP: industry-leading connection pool for high-throughput JDBC access.
 *  - PreparedStatement: pre-compiled SQL prevents SQL injection attacks.
 *  - try-with-resources: guarantees Connection/Statement/ResultSet closure.
 *
 * Full CRUD support:
 *  CREATE  — insertSensorData(), batchInsertSensorData()
 *  READ    — getRecentReadings(), getReadingsByTimeRange(), getActiveAlerts(),
 *            getTotalReadingCount(), getActiveAlertCount(), callGetSensorStats()
 *  UPDATE  — updateDeviceStatus(), resolveAlert()         (also updateLastLogin internal)
 *  DELETE  — deleteOldReadings(), deleteResolvedAlerts()
 *
 * Advanced DB features:
 *  - Trigger        : trg_high_sensor_alert (created by DatabaseSetup)
 *  - Stored Procedure: GetSensorStats()     (called via callGetSensorStats())
 */
public class DatabaseManager {

    private static final Logger LOGGER = Logger.getLogger(DatabaseManager.class.getName());

    private static volatile DatabaseManager instance;
    private volatile HikariDataSource dataSource;

    // =====================================================================
    // Singleton
    // =====================================================================
    private DatabaseManager() {
        initializeDataSource();
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = new DatabaseManager();
                }
            }
        }
        return instance;
    }

    // =====================================================================
    // HikariCP Initialisation
    // =====================================================================
    private void initializeDataSource() {
        this.dataSource = createDataSource(ConfigManager.getInstance());
        LOGGER.info("HikariCP pool initialised: " + dataSource.getPoolName());
    }

    private HikariDataSource createDataSource(ConfigManager config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl       (config.getDatabaseUrl());
        hikariConfig.setUsername      (config.getDatabaseUsername());
        hikariConfig.setPassword      (config.getDatabasePassword());
        hikariConfig.setMaximumPoolSize(config.getHikariMaxPoolSize());
        hikariConfig.setMinimumIdle   (config.getHikariMinIdle());
        hikariConfig.setConnectionTimeout (30000L);
        hikariConfig.setIdleTimeout       (600000L);
        hikariConfig.setMaxLifetime       (1800000L);
        hikariConfig.setPoolName          ("IoTDashboard-HikariPool");

        hikariConfig.addDataSourceProperty("cachePrepStmts",          "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize",        "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit",    "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts",       "true");
        hikariConfig.addDataSourceProperty("useLocalSessionState",     "true");
        hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
        hikariConfig.addDataSourceProperty("cacheResultSetMetadata",   "true");
        hikariConfig.addDataSourceProperty("cacheServerConfiguration", "true");
        hikariConfig.addDataSourceProperty("elideSetAutoCommits",      "true");
        hikariConfig.addDataSourceProperty("maintainTimeStats",        "false");

        return new HikariDataSource(hikariConfig);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Rebuilds the HikariCP pool from the current ConfigManager values.
     *
     * The new pool is created and smoke-tested before the old pool is closed,
     * so a bad password or URL does not break the currently running instance.
     */
    public synchronized void refreshDataSource() {
        HikariDataSource previous = this.dataSource;
        HikariDataSource replacement = null;

        try {
            replacement = createDataSource(ConfigManager.getInstance());

            try (Connection ignored = replacement.getConnection()) {
                // Smoke test successful: swap in the new pool.
            }

            this.dataSource = replacement;
            LOGGER.info("HikariCP pool refreshed successfully.");

        } catch (Exception e) {
            if (replacement != null) {
                replacement.close();
            }
            throw new IllegalStateException(
                    "Failed to reconfigure database connection pool: " + e.getMessage(), e);
        } finally {
            if (previous != null && previous != this.dataSource) {
                previous.close();
            }
        }
    }

    // =====================================================================
    // Authentication
    // =====================================================================

    /**
     * Authenticates a user by fetching their password hash from the database
     * and verifying it using BCrypt.
     */
    public AdminUser authenticateUser(String username, String plainPassword) {
        final String sql = "SELECT user_id, username, password_hash, full_name " +
                           "FROM admin_users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    AdminUser user = new AdminUser(
                            rs.getInt   ("user_id"),
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            rs.getString("full_name")
                    );
                    if (user.validatePassword(plainPassword)) {
                        updateLastLogin(conn, user.getUserId());
                        return user;
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Authentication query failed", e);
        }
        return null;
    }

    private void updateLastLogin(Connection conn, int userId) throws SQLException {
        final String sql = "UPDATE admin_users SET last_login = NOW() WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }
    }

    // =====================================================================
    // CREATE — Sensor Data Insertion
    // =====================================================================

    /**
     * Inserts a single sensor reading into sensor_data.
     * The trigger trg_high_sensor_alert fires automatically on the DB side
     * and creates an alert row in the alerts table if the value exceeds
     * the predefined threshold.
     */
    public void insertSensorData(SensorData data) {
        final String sql = "INSERT INTO sensor_data (device_id, type_id, timestamp, value) " +
                           "VALUES (?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt      (1, data.getDeviceId());
            ps.setInt      (2, data.getTypeId());
            ps.setTimestamp(3, Timestamp.valueOf(data.getTimestamp()));
            ps.setFloat    (4, data.getValue());
            ps.executeUpdate();

        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to insert sensor data: " + data, e);
        }
    }

    /**
     * Batch-inserts a list of sensor readings — significantly more efficient
     * than calling insertSensorData() in a loop for bulk operations.
     * Uses an explicit transaction (BEGIN / COMMIT / ROLLBACK) for atomicity.
     */
    public void batchInsertSensorData(List<SensorData> dataList) {
        if (dataList == null || dataList.isEmpty()) return;

        final String sql = "INSERT INTO sensor_data (device_id, type_id, timestamp, value) " +
                           "VALUES (?, ?, ?, ?)";

        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);  // BEGIN transaction

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (SensorData data : dataList) {
                    ps.setInt      (1, data.getDeviceId());
                    ps.setInt      (2, data.getTypeId());
                    ps.setTimestamp(3, Timestamp.valueOf(data.getTimestamp()));
                    ps.setFloat    (4, data.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();             // COMMIT transaction
            conn.setAutoCommit(true);

        } catch (SQLException e) {
            // ROLLBACK: undo partial batch on any failure to maintain atomicity
            LOGGER.log(Level.SEVERE, "Batch insert failed — attempting ROLLBACK", e);
            if (conn != null) {
                try { conn.rollback(); LOGGER.info("ROLLBACK executed successfully."); }
                catch (SQLException rb) { LOGGER.log(Level.SEVERE, "ROLLBACK also failed", rb); }
            }
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); }
                catch (SQLException ex) { LOGGER.log(Level.WARNING, "Failed to close connection", ex); }
            }
        }
    }

    // =====================================================================
    // READ — Sensor Data Retrieval
    // =====================================================================

    /**
     * Retrieves the most recent N readings for a specific sensor type.
     * Uses a JOIN with sensor_types to include measurement name and unit.
     */
    public List<SensorData> getRecentReadings(int typeId, int limit) {
        final String sql =
                "SELECT sd.device_id, sd.type_id, st.measurement, st.unit, " +
                "       sd.value, sd.timestamp " +
                "FROM sensor_data sd " +
                "JOIN sensor_types st ON sd.type_id = st.type_id " +
                "WHERE sd.type_id = ? " +
                "ORDER BY sd.timestamp DESC LIMIT ?";

        List<SensorData> results = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, typeId);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new SensorData(
                            rs.getInt      ("device_id"),
                            rs.getInt      ("type_id"),
                            rs.getString   ("measurement"),
                            rs.getString   ("unit"),
                            rs.getFloat    ("value"),
                            rs.getTimestamp("timestamp").toLocalDateTime()
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to retrieve recent readings", e);
        }
        return results;
    }

    /**
     * Retrieves all readings within a specified time range for report generation.
     */
    public List<SensorData> getReadingsByTimeRange(LocalDateTime from, LocalDateTime to) {
        final String sql =
                "SELECT sd.device_id, sd.type_id, st.measurement, st.unit, " +
                "       sd.value, sd.timestamp " +
                "FROM sensor_data sd " +
                "JOIN sensor_types st ON sd.type_id = st.type_id " +
                "WHERE sd.timestamp BETWEEN ? AND ? " +
                "ORDER BY sd.timestamp ASC";

        List<SensorData> results = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setTimestamp(1, Timestamp.valueOf(from));
            ps.setTimestamp(2, Timestamp.valueOf(to));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new SensorData(
                            rs.getInt      ("device_id"),
                            rs.getInt      ("type_id"),
                            rs.getString   ("measurement"),
                            rs.getString   ("unit"),
                            rs.getFloat    ("value"),
                            rs.getTimestamp("timestamp").toLocalDateTime()
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to retrieve readings for time range", e);
        }
        return results;
    }

    /** Total count of sensor readings in the database. */
    public long getTotalReadingCount() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sensor_data")) {
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Count query failed", e);
        }
        return 0L;
    }

    // =====================================================================
    // READ — Alert Retrieval
    // =====================================================================

    /**
     * Retrieves unresolved alerts ordered by most recent first.
     * Each row is returned as a String array: [alert_id, message, actual_val, created_at].
     */
    public List<String[]> getActiveAlerts(int limit) {
        final String sql =
                "SELECT a.alert_id, a.alert_message, a.actual_val, " +
                "       a.threshold_val, a.created_at " +
                "FROM alerts a " +
                "WHERE a.is_resolved = 0 " +
                "ORDER BY a.created_at DESC LIMIT ?";

        List<String[]> results = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new String[]{
                        String.valueOf(rs.getInt("alert_id")),
                        rs.getString("alert_message"),
                        String.format("%.2f", rs.getFloat("actual_val")),
                        String.format("%.2f", rs.getFloat("threshold_val")),
                        rs.getTimestamp("created_at").toString()
                    });
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to retrieve alerts", e);
        }
        return results;
    }

    /** Count of active (unresolved) alerts. */
    public int getActiveAlertCount() {
        final String sql = "SELECT COUNT(*) FROM alerts WHERE is_resolved = 0";
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Alert count query failed", e);
        }
        return 0;
    }

    // =====================================================================
    // READ — VIEW Query (vw_active_alerts)
    // =====================================================================

    /**
     * Queries the vw_active_alerts VIEW to retrieve enriched active alert rows.
     * The view encapsulates the three-table JOIN (alerts ⟶ devices ⟶ sensor_types)
     * so the application layer does not repeat the JOIN logic.
     *
     * Each returned array: [alert_id, device_name, location, measurement,
     *                        actual_val, threshold_val, created_at]
     *
     * Demonstrates: CREATE VIEW / SELECT from VIEW (Advanced SQL — Chapter 6)
     */
    public List<String[]> getActiveAlertsFromView(int limit) {
        final String sql =
                "SELECT alert_id, device_name, location, measurement, unit, " +
                "       alert_message, actual_val, threshold_val, created_at " +
                "FROM   vw_active_alerts " +
                "LIMIT  ?";

        List<String[]> results = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new String[]{
                        String.valueOf(rs.getInt("alert_id")),
                        rs.getString("device_name"),
                        rs.getString("location"),
                        rs.getString("measurement") + " (" + rs.getString("unit") + ")",
                        String.format("%.2f", rs.getFloat("actual_val")),
                        String.format("%.2f", rs.getFloat("threshold_val")),
                        rs.getTimestamp("created_at").toString()
                    });
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query vw_active_alerts view", e);
        }
        return results;
    }

    // =====================================================================
    // READ — Nested Subquery
    // =====================================================================

    /**
     * Returns the list of devices that currently have at least one active alert.
     * Uses a nested subquery (IN with SELECT) on the alerts table.
     *
     * SQL:
     *   SELECT device_id, device_name, location
     *   FROM   devices
     *   WHERE  device_id IN (
     *              SELECT DISTINCT device_id FROM alerts WHERE is_resolved = 0
     *          );
     *
     * Demonstrates: Nested / subquery requirement — Chapter 6, Queries section.
     *
     * @return list of String arrays [device_id, device_name, location]
     */
    public List<String[]> getDevicesWithActiveAlerts() {
        final String sql =
                "SELECT device_id, device_name, location " +
                "FROM   devices " +
                "WHERE  device_id IN ( " +
                "    SELECT DISTINCT device_id FROM alerts WHERE is_resolved = 0 " +
                ")";

        List<String[]> results = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                results.add(new String[]{
                    String.valueOf(rs.getInt("device_id")),
                    rs.getString("device_name"),
                    rs.getString("location")
                });
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Nested subquery (getDevicesWithActiveAlerts) failed", e);
        }
        return results;
    }

    // =====================================================================
    // READ — Stored Procedure Invocation
    // =====================================================================

    /**
     * Calls the GetSensorStats stored procedure and returns the result as
     * a formatted string for display.
     *
     * @param typeId  sensor type (1=Temp, 2=Humidity, 3=Voltage, 4=Power)
     * @param hours   look-back window in hours
     * @return human-readable summary string
     */
    public String callGetSensorStats(int typeId, int hours) {
        final String sql = "{CALL GetSensorStats(?, ?)}";

        try (Connection conn = getConnection();
             CallableStatement cs = conn.prepareCall(sql)) {

            cs.setInt(1, typeId);
            cs.setInt(2, hours);

            try (ResultSet rs = cs.executeQuery()) {
                if (rs.next()) {
                    return String.format(
                        "[%s (%s)] over last %d h — " +
                        "Count: %d | Avg: %s | Min: %s | Max: %s | StdDev: %s",
                        rs.getString("sensor_type"),
                        rs.getString("unit"),
                        hours,
                        rs.getLong  ("total_readings"),
                        rs.getString("avg_value"),
                        rs.getString("min_value"),
                        rs.getString("max_value"),
                        rs.getString("std_dev")
                    );
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "GetSensorStats procedure call failed", e);
        }
        return "No data available for the selected period.";
    }

    // =====================================================================
    // UPDATE — Device Management
    // =====================================================================

    /**
     * Updates the active/inactive status of a device.
     * (UPDATE operation — CRUD compliance)
     *
     * @param deviceId target device
     * @param status   1 = Active, 0 = Inactive
     * @return true if the row was updated
     */
    public boolean updateDeviceStatus(int deviceId, int status) {
        final String sql = "UPDATE devices SET status = ? WHERE device_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, status);
            ps.setInt(2, deviceId);
            int affected = ps.executeUpdate();
            LOGGER.info("updateDeviceStatus: device_id=" + deviceId +
                        " → status=" + status + " (" + affected + " row(s))");
            return affected > 0;

        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "updateDeviceStatus failed", e);
            return false;
        }
    }

    // =====================================================================
    // UPDATE — Alert Management
    // =====================================================================

    /**
     * Marks a specific alert as resolved.
     * (UPDATE operation — CRUD compliance)
     *
     * @param alertId the alert to resolve
     * @return true if the row was updated
     */
    public boolean resolveAlert(int alertId) {
        final String sql = "UPDATE alerts SET is_resolved = 1 WHERE alert_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, alertId);
            int affected = ps.executeUpdate();
            LOGGER.info("resolveAlert: alert_id=" + alertId +
                        " (" + affected + " row(s) updated)");
            return affected > 0;

        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "resolveAlert failed", e);
            return false;
        }
    }

    // =====================================================================
    // DELETE — Data Purge
    // =====================================================================

    /**
     * Deletes sensor readings older than the specified number of hours.
     * This is the DELETE CRUD operation — also used by the "Clear Old Data"
     * button in the dashboard to purge stale readings from the database.
     *
     * Uses an explicit transaction (BEGIN / COMMIT / ROLLBACK) for atomicity.
     *
     * @param olderThanHours readings older than this many hours are removed
     * @return number of rows deleted
     */
    public int deleteOldReadings(int olderThanHours) {
        // olderThanHours == 0 means delete ALL readings
        final String sql = (olderThanHours == 0)
                ? "DELETE FROM sensor_data"
                : "DELETE FROM sensor_data WHERE timestamp < NOW() - INTERVAL ? HOUR";

        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);  // BEGIN transaction

            int deleted;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (olderThanHours > 0) ps.setInt(1, olderThanHours);
                deleted = ps.executeUpdate();
            }

            conn.commit();             // COMMIT
            conn.setAutoCommit(true);
            LOGGER.info("deleteOldReadings: removed " + deleted +
                        (olderThanHours == 0 ? " rows (full wipe)." : " rows older than " + olderThanHours + " hours."));
            return deleted;

        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "deleteOldReadings failed — attempting ROLLBACK", e);
            if (conn != null) {
                try { conn.rollback(); LOGGER.info("ROLLBACK executed successfully."); }
                catch (SQLException rb) { LOGGER.log(Level.SEVERE, "ROLLBACK also failed", rb); }
            }
            return 0;
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); }
                catch (SQLException ex) { LOGGER.log(Level.WARNING, "Failed to close connection", ex); }
            }
        }
    }

    /**
     * Deletes all alerts that have been resolved.
     * Keeps the alerts table tidy after bulk acknowledgement sessions.
     *
     * Uses an explicit transaction (BEGIN / COMMIT / ROLLBACK) for atomicity.
     *
     * @return number of rows deleted
     */
    public int deleteResolvedAlerts() {
        final String sql = "DELETE FROM alerts WHERE is_resolved = 1";

        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);  // BEGIN transaction

            int deleted;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                deleted = ps.executeUpdate();
            }

            conn.commit();             // COMMIT
            conn.setAutoCommit(true);
            LOGGER.info("deleteResolvedAlerts: removed " + deleted + " resolved alert(s).");
            return deleted;

        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "deleteResolvedAlerts failed — attempting ROLLBACK", e);
            if (conn != null) {
                try { conn.rollback(); LOGGER.info("ROLLBACK executed successfully."); }
                catch (SQLException rb) { LOGGER.log(Level.SEVERE, "ROLLBACK also failed", rb); }
            }
            return 0;
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); }
                catch (SQLException ex) { LOGGER.log(Level.WARNING, "Failed to close connection", ex); }
            }
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            LOGGER.info("HikariCP pool shut down cleanly.");
        }
    }

    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }
}
