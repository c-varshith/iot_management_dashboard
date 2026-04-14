package com.iot.dashboard;

import com.iot.dashboard.model.SensorData;
import com.iot.dashboard.util.PasswordUtil;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DatabaseManagerTest — uses an H2 in-memory database to validate
 * SQL query logic without connecting to a real MySQL server.
 *
 * H2 approach benefits:
 *  - No external infrastructure required (DB spins up in JVM RAM).
 *  - Complete isolation between test runs — DB is destroyed after each class.
 *  - Validates actual SQL syntax (unlike Mockito which bypasses queries).
 *  - Zero disk I/O — test suite executes in milliseconds.
 *
 * These tests directly exercise the SQL logic used by DatabaseManager's
 * core methods, replicating the same schema and query patterns.
 */
@DisplayName("Database Layer SQL Logic Tests (H2 In-Memory)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseManagerTest {

    private static Connection conn;

    // =====================================================================
    // Setup & Teardown
    // =====================================================================
    @BeforeAll
    static void setupH2Database() throws SQLException {
        // H2 in-memory DB — URL uses MySQL compatibility mode
        conn = DriverManager.getConnection(
                "jdbc:h2:mem:iot_test;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");

        // Create schema matching production
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS sensor_types (" +
                "  type_id     INT         NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                "  measurement VARCHAR(50) NOT NULL," +
                "  unit        VARCHAR(20) NOT NULL" +
                ")"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS devices (" +
                "  device_id   INT          NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                "  device_name VARCHAR(100) NOT NULL," +
                "  location    VARCHAR(100) DEFAULT 'Unknown'," +
                "  status      TINYINT      DEFAULT 1" +
                ")"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS sensor_data (" +
                "  reading_id BIGINT    NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                "  device_id  INT       NOT NULL," +
                "  type_id    INT       NOT NULL," +
                "  timestamp  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  value      FLOAT     NOT NULL" +
                ")"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS admin_users (" +
                "  user_id       INT          NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                "  username      VARCHAR(50)  NOT NULL UNIQUE," +
                "  password_hash VARCHAR(255) NOT NULL," +
                "  full_name     VARCHAR(100) DEFAULT NULL" +
                ")"
            );

            // Seed reference data
            st.execute("INSERT INTO sensor_types (type_id, measurement, unit) " +
                       "VALUES (1,'Temperature','°C'),(2,'Humidity','%')," +
                       "(3,'Voltage','V'),(4,'Active Power','W')");
            st.execute("INSERT INTO devices (device_id, device_name) VALUES (1,'Test Meter A')");
        }
    }

    @AfterAll
    static void teardownH2Database() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @BeforeEach
    void clearSensorData() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM sensor_data");
        }
    }

    // =====================================================================
    // Test 1: Insert and retrieve sensor data
    // =====================================================================
    @Test
    @Order(1)
    @DisplayName("Should insert a sensor reading and retrieve it correctly")
    void testInsertAndRetrieveSensorData() throws SQLException {
        // === INSERT ===
        String insertSql = "INSERT INTO sensor_data (device_id, type_id, timestamp, value) " +
                           "VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setInt      (1, 1);
            ps.setInt      (2, 1); // Temperature
            ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            ps.setFloat    (4, 23.5f);
            ps.executeUpdate();
        }

        // === RETRIEVE ===
        String selectSql = "SELECT sd.device_id, sd.type_id, st.measurement, st.unit, " +
                           "       sd.value, sd.timestamp " +
                           "FROM sensor_data sd " +
                           "JOIN sensor_types st ON sd.type_id = st.type_id " +
                           "WHERE sd.type_id = 1 ORDER BY sd.timestamp DESC LIMIT 10";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(selectSql)) {
            assertTrue(rs.next(), "Should have retrieved at least one row");
            assertEquals(1,        rs.getInt("device_id"));
            assertEquals(1,        rs.getInt("type_id"));
            assertEquals("Temperature", rs.getString("measurement"));
            assertEquals("°C",     rs.getString("unit"));
            assertEquals(23.5f,    rs.getFloat("value"), 0.001f);
        }
    }

    // =====================================================================
    // Test 2: PreparedStatement prevents SQL injection
    // =====================================================================
    @Test
    @Order(2)
    @DisplayName("PreparedStatement parameterises input — SQL injection attempt returns no rows")
    void testSqlInjectionPrevention() throws SQLException {
        // Seed a legitimate user
        String hash = PasswordUtil.hashPassword("securePass1");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO admin_users (username, password_hash, full_name) VALUES (?,?,?)")) {
            ps.setString(1, "testadmin");
            ps.setString(2, hash);
            ps.setString(3, "Test Admin");
            ps.executeUpdate();
        }

        // SQL injection attempt: classic tautology ' OR '1'='1
        String injectionAttempt = "' OR '1'='1";
        String safeSql = "SELECT user_id FROM admin_users WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(safeSql)) {
            ps.setString(1, injectionAttempt);
            try (ResultSet rs = ps.executeQuery()) {
                // PreparedStatement treats the injection as a literal string — no rows returned
                assertFalse(rs.next(), "SQL injection should not return any rows via PreparedStatement");
            }
        }
    }

    // =====================================================================
    // Test 3: BCrypt password hashing and verification
    // =====================================================================
    @Test
    @Order(3)
    @DisplayName("BCrypt hash and verify round-trip works correctly")
    void testBcryptPasswordRoundTrip() {
        String password = "admin123";
        String hash     = PasswordUtil.hashPassword(password);

        assertNotNull(hash);
        assertTrue(hash.startsWith("$2a$"),
                "BCrypt hash should start with $2a$ version identifier");
        assertEquals(60, hash.length(),
                "BCrypt hash should always be exactly 60 characters");

        // Correct password verifies
        assertTrue(PasswordUtil.checkPassword(password, hash),
                "Correct password should verify against its hash");

        // Wrong password fails
        assertFalse(PasswordUtil.checkPassword("wrongpassword", hash),
                "Wrong password should not verify");

        // Null inputs handled gracefully
        assertFalse(PasswordUtil.checkPassword(null, hash),
                "Null password should return false");
        assertFalse(PasswordUtil.checkPassword(password, null),
                "Null hash should return false");
    }

    // =====================================================================
    // Test 4: Time range query
    // =====================================================================
    @Test
    @Order(4)
    @DisplayName("Time-range query returns only readings within the specified window")
    void testTimeRangeQuery() throws SQLException {
        LocalDateTime now    = LocalDateTime.now();
        LocalDateTime past2h = now.minusHours(2);
        LocalDateTime past1h = now.minusHours(1);
        LocalDateTime future = now.plusHours(1);

        String insert = "INSERT INTO sensor_data (device_id, type_id, timestamp, value) VALUES (?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            // Reading 2 hours ago — should be EXCLUDED from [1h_ago, now] range
            ps.setInt      (1, 1); ps.setInt(2, 3);
            ps.setTimestamp(3, Timestamp.valueOf(past2h)); ps.setFloat(4, 229f);
            ps.addBatch();
            // Reading 30 minutes ago — should be INCLUDED
            ps.setInt      (1, 1); ps.setInt(2, 3);
            ps.setTimestamp(3, Timestamp.valueOf(now.minusMinutes(30))); ps.setFloat(4, 231f);
            ps.addBatch();
            ps.executeBatch();
        }

        String rangeSql = "SELECT COUNT(*) FROM sensor_data WHERE timestamp BETWEEN ? AND ?";
        try (PreparedStatement ps = conn.prepareStatement(rangeSql)) {
            ps.setTimestamp(1, Timestamp.valueOf(past1h));
            ps.setTimestamp(2, Timestamp.valueOf(future));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1),
                        "Only the reading within the time range should be returned");
            }
        }
    }

    // =====================================================================
    // Test 5: Batch insert efficiency test
    // =====================================================================
    @Test
    @Order(5)
    @DisplayName("Batch insert correctly persists multiple readings in one transaction")
    void testBatchInsert() throws SQLException {
        String insert = "INSERT INTO sensor_data (device_id, type_id, timestamp, value) VALUES (?,?,?,?)";
        int batchSize = 100;

        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            for (int i = 0; i < batchSize; i++) {
                ps.setInt      (1, 1);
                ps.setInt      (2, (i % 4) + 1); // cycle through types 1-4
                ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
                ps.setFloat    (4, 20.0f + i * 0.1f);
                ps.addBatch();
            }
            int[] results = ps.executeBatch();
            conn.commit();

            assertEquals(batchSize, results.length,
                    "All " + batchSize + " rows should have been batched");
        }
        conn.setAutoCommit(true);

        // Verify count
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sensor_data")) {
            assertTrue(rs.next());
            assertEquals(batchSize, rs.getInt(1),
                    "Database should contain exactly " + batchSize + " inserted rows");
        }
    }
}
