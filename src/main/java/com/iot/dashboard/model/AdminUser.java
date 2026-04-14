package com.iot.dashboard.model;

import com.iot.dashboard.util.PasswordUtil;
import java.time.LocalDateTime;

/**
 * Encapsulates the administrator user model.
 * Credentials are validated against BCrypt-hashed passwords stored in the DB.
 */
public class AdminUser {

    private final int           userId;
    private final String        username;
    private final String        passwordHash;
    private final String        fullName;
    private       LocalDateTime lastLogin;

    public AdminUser(int userId, String username, String passwordHash, String fullName) {
        this.userId       = userId;
        this.username     = username;
        this.passwordHash = passwordHash;
        this.fullName     = fullName;
    }

    /**
     * Validates a plaintext password against the stored BCrypt hash.
     *
     * @param plainTextPassword the password entered by the user
     * @return true if the password matches the stored hash
     */
    public boolean validatePassword(String plainTextPassword) {
        return PasswordUtil.checkPassword(plainTextPassword, this.passwordHash);
    }

    public int           getUserId()      { return userId; }
    public String        getUsername()    { return username; }
    public String        getPasswordHash(){ return passwordHash; }
    public String        getFullName()    { return fullName; }
    public LocalDateTime getLastLogin()   { return lastLogin; }
    public void          setLastLogin(LocalDateTime t) { this.lastLogin = t; }

    @Override
    public String toString() {
        return String.format("AdminUser{id=%d, username='%s', fullName='%s'}", userId, username, fullName);
    }
}
