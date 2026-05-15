package com.iot.dashboard.controller;

import com.iot.dashboard.database.DatabaseManager;
import com.iot.dashboard.database.DatabaseSetup;
import com.iot.dashboard.util.ConfigManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.logging.Logger;

/**
 * FirstRunController — shown on first launch (or when no config file exists).
 *
 * Collects MySQL credentials, tests the connection, saves to
 * ~/.iot-dashboard/config.properties, then proceeds to the login screen.
 */
public class FirstRunController implements Initializable {

    private static final Logger LOGGER = Logger.getLogger(FirstRunController.class.getName());

    @FXML private TextField     dbUrlField;
    @FXML private TextField     usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label         statusLabel;
    @FXML private Label         configPathLabel;
    @FXML private Button        connectButton;
    @FXML private Button        exitButton;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        ConfigManager config = ConfigManager.getInstance();

        // Pre-fill with current defaults so user only has to change password
        dbUrlField.setText(config.getDatabaseUrl());
        usernameField.setText(config.getDatabaseUsername());
        passwordField.setText("");

        configPathLabel.setText("Config will be saved to: " + config.getConfigPath());

        connectButton.setOnAction(e -> handleConnect());
        exitButton.setOnAction(e -> Platform.exit());

        // Allow Enter key to trigger connect
        passwordField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) handleConnect(); });

        connectButton.setDefaultButton(true);
    }

    private void handleConnect() {
        String url      = dbUrlField.getText().trim();
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (url.isEmpty() || username.isEmpty()) {
            showError("Database URL and username are required.");
            return;
        }

        setStatus("Testing connection...", "#00d4ff");
        connectButton.setDisable(true);

        // Run off the JavaFX thread so the UI doesn't freeze during connection attempt
        Thread worker = new Thread(() -> {
            try {
                ConfigManager config = ConfigManager.getInstance();
                config.setDatabaseUrl(url);
                config.setDatabaseUsername(username);
                config.setDatabasePassword(password);

                // Rebuild HikariCP pool with new credentials and smoke-test it
                DatabaseManager.getInstance().refreshDataSource();

                // Run schema setup (idempotent — safe if tables already exist)
                boolean schemaReady = DatabaseSetup.runSetup();
                if (!schemaReady) {
                    throw new IllegalStateException(
                        "Connected to MySQL but schema setup failed.\n" +
                        "Make sure the 'iot_dashboard' database exists:\n" +
                        "  CREATE DATABASE iot_dashboard;"
                    );
                }

                // Persist credentials so they load automatically on next launch
                config.saveConfig();

                Platform.runLater(() -> {
                    setStatus("Connected! Launching...", "#4caf50");
                    try {
                        com.iot.dashboard.DashboardApplication.showLoginScreen();
                        closeStage();
                    } catch (Exception ex) {
                        LOGGER.severe("Failed to show login screen: " + ex.getMessage());
                        showError("Connection succeeded but failed to open login screen.");
                    }
                });

            } catch (Exception ex) {
                LOGGER.warning("First-run connection failed: " + ex.getMessage());
                Platform.runLater(() -> {
                    connectButton.setDisable(false);
                    showError("Connection failed: " + friendlyMessage(ex.getMessage()));
                });
            }
        }, "firstrun-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void setStatus(String message, String colour) {
        statusLabel.setText(message);
        statusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: " + colour + "; -fx-padding: 4 0 0 0;");
    }

    private void showError(String message) {
        setStatus(message, "#ff6b6b");
    }

    private void closeStage() {
        Stage stage = (Stage) connectButton.getScene().getWindow();
        stage.close();
    }

    /** Strips verbose JDBC/HikariCP boilerplate from error messages. */
    private String friendlyMessage(String raw) {
        if (raw == null) return "Unknown error.";
        if (raw.contains("Access denied"))
            return "Access denied — wrong username or password.";
        if (raw.contains("Communications link failure") || raw.contains("Connection refused"))
            return "Cannot reach MySQL — is the server running on port 3306?";
        if (raw.contains("Unknown database"))
            return "Database 'iot_dashboard' does not exist. Create it first:\n  CREATE DATABASE iot_dashboard;";
        if (raw.length() > 140)
            return raw.substring(0, 140) + "...";
        return raw;
    }
}
