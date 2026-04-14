package com.iot.dashboard.database;

import com.iot.dashboard.model.AdminUser;
import com.iot.dashboard.model.SensorData;
import com.iot.dashboard.model.SensorType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DatabaseManager — Singleton data access layer.
 *
 * Design decisions:
 *  - Singleton pattern: guarantees one pool instance per JVM lifecycle.
 *  - HikariCP: industry-leading connection pool for high-throughput JDBC access.
 *  - PreparedStatement: pre-compiled SQL prevents SQL injection attacks.
 *  - try-with-resources: guarantees Connection/Statement/ResultSet closure,
 *    eliminating memory leaks in long-running simulation sessions.
 *
 * Thread safety: HikariCP is fully thread-safe. Multiple simulation threads
 * may call insertSensorData() concurrently without synchronisation overhead.
 */
public class DatabaseManager {

    private static final Logger LOGGER = Logger.getLogger(DatabaseManager.class.getName());

    /** Volatile ensures visibility of the instance across CPU caches in all threads. */
    private static volatile DatabaseManager instance;

    private HikariDataSource dataSource;

    // =====================================================================
    // Singleton constructor — private to prevent external instantiation
    // =====================================================================
    private DatabaseManager() {
        try {
            initializeDataSource();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load database.properties", e);
        }
    }

    /**
     * Thread-safe double-checked locking Singleton accessor.
     * Ensures only one DatabaseManager instance is created even under
     * heavy concurrent access from multiple simulation threads.
     */
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
    private void initializeDataSource() throws IOException {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("database.properties")) {
            if (is == null) {
                throw new IOException("database.properties not found on classpath");
            }
            props.load(is);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl       (props.getProperty("db.url"));
        config.setUsername      (props.getProperty("db.username"));
        config.setPassword      (props.getProperty("db.password"));
        config.setMaximumPoolSize(Integer.parseInt(props.getProperty("hikari.maximumPoolSize", "10")));
        config.setMinimumIdle   (Integer.parseInt(props.getProperty("hikari.minimumIdle",    "2")));
        config.setConnectionTimeout (Long.parseLong(props.getProperty("hikari.connectionTimeout","30000")));
        config.setIdleTimeout       (Long.parseLong(props.getProperty("hikari.idleTimeout",  "600000")));
        config.setMaxLifetime       (Long.parseLong(props.getProperty("hikari.maxLifetime",  "1800000")));
        config.setPoolName          (props.getProperty("hikari.poolName", "IoTDashboard-HikariPool"));

        // Performance tuning
        config.addDataSourceProperty("cachePrepStmts",          "true");
        config.addDataSourceProperty("prepStmtCacheSize",        "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit",    "2048");
        config.addDataSourceProperty("useServerPrepStmts",       "true");
        config.addDataSourceProperty("useLocalSessionState",     "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata",   "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits",      "true");
        config.addDataSourceProperty("maintainTimeStats",        "false");

        this.dataSource = new HikariDataSource(config);
        LOGGER.info("HikariCP pool initialised: " + config.getPoolName());
    }

    /**
     * Provides a connection from the pool to callers.
     * ALWAYS use in a try-with-resources block.
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // =====================================================================
    // Authentication
    // =====================================================================

    /**
     * Authenticates a user by fetching their password hash from the database
     * and verifying it using BCrypt. Uses PreparedStatement to prevent SQL injection.
     *
     * @param username        the entered username
     * @param plainPassword   the entered plaintext password
     * @return the authenticated AdminUser, or null if credentials are invalid
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
                    // BCrypt verification — compare plaintext against stored hash
                    if (user.validatePassword(plainPassword)) {
                        updateLastLogin(conn, user.getUserId());
                        return user;
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Authentication query failed", e);
        }
        return null; // Invalid credentials
    }

    private void updateLastLogin(Connection conn, int userId) throws SQLException {
        final String sql = "UPDATE admin_users SET last_login = NOW() WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }
    }

    // =====================================================================
    // Sensor Data Insertion
    // =====================================================================

    /**
     * Inserts a single sensor reading into the sensor_data table.
     * Called by the simulation engine for every generated reading.
     *
     * Uses a PreparedStatement to prevent SQL injection and to benefit
     * from server-side statement caching via HikariCP.
     */
    public void insertSensorData(SensorData data) {
        final String sql = "INSERT INTO sensor_data (device_id, type_id, timestamp, value) " +
                           "VALUES (?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt       (1, data.getDeviceId());
            ps.setInt       (2, data.getTypeId());
            ps.setTimestamp (3, Timestamp.valueOf(data.getTimestamp()));
            ps.setFloat     (4, data.getValue());
            ps.executeUpdate();

        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to insert sensor data: " + data, e);
        }
    }

    /**
     * Batch-inserts a list of sensor readings — significantly more efficient
     * than calling insertSensorData() in a loop for bulk operations.
     */
    public void batchInsertSensorData(List<SensorData> dataList) {
        if (dataList == null || dataList.isEmpty()) return;

        final String sql = "INSERT INTO sensor_data (device_id, type_id, timestamp, value) " +
                           "VALUES (?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);

            for (SensorData data : dataList) {
                ps.setInt      (1, data.getDeviceId());
                ps.setInt      (2, data.getTypeId());
                ps.setTimestamp(3, Timestamp.valueOf(data.getTimestamp()));
                ps.setFloat    (4, data.getValue());
                ps.addBatch();
            }

            ps.executeBatch();
            conn.commit();
            conn.setAutoCommit(true);

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Batch insert failed", e);
        }
    }

    // =====================================================================
    // Sensor Data Retrieval
    // =====================================================================

    /**
     * Retrieves the most recent N readings for a specific sensor type.
     * Used by the dashboard to populate the data table and generate reports.
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

    /**
     * Returns the total count of stored readings — used for dashboard statistics.
     */
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
    // Lifecycle
    // =====================================================================

    /**
     * Closes the HikariCP pool and all underlying connections.
     * Must be called when the application shuts down to release DB resources.
     */
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
