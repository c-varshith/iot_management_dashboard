package com.iot.dashboard.util;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Utility class for BCrypt password hashing and verification.
 *
 * BCrypt automatically embeds the salt within the hash string,
 * so no separate salt management is required.
 *
 * Work factor (log rounds) = 10 is the industry standard balance
 * between security strength and authentication latency (~100ms per check).
 */
public final class PasswordUtil {

    /** BCrypt work factor — cost parameter for hashing rounds (2^10 = 1024 iterations). */
    private static final int BCRYPT_LOG_ROUNDS = 10;

    // Prevent instantiation — this is a static utility class
    private PasswordUtil() {}

    /**
     * Hashes a plaintext password using BCrypt with an auto-generated salt.
     *
     * @param plainTextPassword the raw password to hash
     * @return a BCrypt hash string (60 characters) safe to store in the database
     */
    public static String hashPassword(String plainTextPassword) {
        if (plainTextPassword == null || plainTextPassword.isBlank()) {
            throw new IllegalArgumentException("Password cannot be null or blank.");
        }
        return BCrypt.hashpw(plainTextPassword, BCrypt.gensalt(BCRYPT_LOG_ROUNDS));
    }

    /**
     * Verifies a plaintext password against a previously stored BCrypt hash.
     *
     * @param plainTextPassword the candidate password entered by the user
     * @param hashedPassword    the BCrypt hash retrieved from the database
     * @return true if the password matches; false otherwise
     */
    public static boolean checkPassword(String plainTextPassword, String hashedPassword) {
        if (plainTextPassword == null || hashedPassword == null) {
            return false;
        }
        try {
            return BCrypt.checkpw(plainTextPassword, hashedPassword);
        } catch (IllegalArgumentException e) {
            // Malformed hash in database — treat as invalid credential
            return false;
        }
    }
}
