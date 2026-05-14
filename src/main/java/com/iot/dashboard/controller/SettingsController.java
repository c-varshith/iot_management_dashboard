package com.iot.dashboard.controller;

import com.iot.dashboard.util.AlertUtil;
import com.iot.dashboard.util.ConfigManager;
import com.iot.dashboard.database.DatabaseManager;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.util.Properties;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.logging.Logger;

/**
 * SettingsController — Manages the settings dialog.
 *
 * Responsibilities:
 *   1. Load current configuration from ConfigManager
 *   2. Display in form fields
 *   3. Validate user input on save
 *   4. Save to external config file
 *   5. Notify parent to restart connections
 */
public class SettingsController implements Initializable {

    private static final Logger LOGGER = Logger.getLogger(SettingsController.class.getName());

    // =========================================================================
    // FXML Injected Components
    // =========================================================================

    @FXML private TextField dbUrlField;
    @FXML private TextField dbUsernameField;
    @FXML private PasswordField dbPasswordField;

    @FXML private CheckBox useRealSensorCheckbox;
    @FXML private TextField serialPortField;
    @FXML private TextField baudRateField;

    @FXML private Label statusLabel;
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    // =========================================================================
    // Callback Interface
    // =========================================================================

    private Runnable onSettingsSaved;

    public void setOnSettingsSaved(Runnable callback) {
        this.onSettingsSaved = callback;
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        loadCurrentSettings();
        attachEventHandlers();
    }

    private void loadCurrentSettings() {
        ConfigManager config = ConfigManager.getInstance();

        dbUrlField.setText(config.getDatabaseUrl());
        dbUsernameField.setText(config.getDatabaseUsername());
        dbPasswordField.setText(config.getDatabasePassword());

        useRealSensorCheckbox.setSelected(config.isUseRealSensor());
        serialPortField.setText(config.getSerialPort());
        baudRateField.setText(String.valueOf(config.getBaudRate()));

        clearStatus();
    }

    private void attachEventHandlers() {
        saveButton.setOnAction(event -> handleSave());
        cancelButton.setOnAction(event -> handleCancel());

        // Clear status message when user starts editing
        dbUrlField.setOnKeyTyped(e -> clearStatus());
        dbUsernameField.setOnKeyTyped(e -> clearStatus());
        dbPasswordField.setOnKeyTyped(e -> clearStatus());
        serialPortField.setOnKeyTyped(e -> clearStatus());
        baudRateField.setOnKeyTyped(e -> clearStatus());
        useRealSensorCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> clearStatus());
    }

    // =========================================================================
    // Save Handler
    // =========================================================================

    private void handleSave() {
        // Validate inputs
        if (!validateInputs()) {
            return;
        }

        ConfigManager config = ConfigManager.getInstance();
        Properties snapshot = config.snapshot();

        try {
            // Update configuration values
            config.setDatabaseUrl(dbUrlField.getText().trim());
            config.setDatabaseUsername(dbUsernameField.getText().trim());
            config.setDatabasePassword(dbPasswordField.getText());

            config.setUseRealSensor(useRealSensorCheckbox.isSelected());
            config.setSerialPort(serialPortField.getText().trim());
            config.setBaudRate(Integer.parseInt(baudRateField.getText().trim()));

            // Validate database credentials immediately so the user gets feedback
            // before we persist the new configuration to disk.
            DatabaseManager.getInstance().refreshDataSource();

            // Save to external file
            config.saveConfig();

            showSuccess("Settings saved successfully! Apply these changes?");

            // Notify parent controller (DashboardController) to restart connections
            if (onSettingsSaved != null) {
                onSettingsSaved.run();
            }

            // Close dialog
            closeDialog();

        } catch (Exception e) {
            config.restore(snapshot);
            try {
                DatabaseManager.getInstance().refreshDataSource();
            } catch (Exception revertError) {
                LOGGER.warning("Failed to restore previous database pool after settings error: "
                        + revertError.getMessage());
            }
            showError("Failed to save settings: " + e.getMessage());
            LOGGER.severe("Settings save failed: " + e);
        }
    }

    private boolean validateInputs() {
        String url = dbUrlField.getText().trim();
        String username = dbUsernameField.getText().trim();
        String port = serialPortField.getText().trim();
        String baudRateStr = baudRateField.getText().trim();

        // Database URL validation
        if (url.isEmpty()) {
            showError("Database URL cannot be empty.");
            return false;
        }
        if (!url.startsWith("jdbc:mysql://")) {
            showError("Database URL must start with 'jdbc:mysql://'");
            return false;
        }

        // Username validation
        if (username.isEmpty()) {
            showError("Database username cannot be empty.");
            return false;
        }

        // Serial port validation (only if real sensor is enabled)
        if (useRealSensorCheckbox.isSelected()) {
            if (port.isEmpty()) {
                showError("Serial port cannot be empty when real sensor is enabled.");
                return false;
            }
        }

        // Baud rate validation
        if (baudRateStr.isEmpty()) {
            showError("Baud rate cannot be empty.");
            return false;
        }
        try {
            int baudRate = Integer.parseInt(baudRateStr);
            if (baudRate < 300 || baudRate > 921600) {
                showError("Baud rate must be between 300 and 921600.");
                return false;
            }
        } catch (NumberFormatException e) {
            showError("Baud rate must be a valid number.");
            return false;
        }

        return true;
    }

    // =========================================================================
    // Cancel Handler
    // =========================================================================

    private void handleCancel() {
        closeDialog();
    }

    // =========================================================================
    // UI Feedback
    // =========================================================================

    private void showSuccess(String message) {
        statusLabel.setText("✅ " + message);
        statusLabel.setStyle("-fx-text-fill: #4caf50; -fx-font-size: 11;");
    }

    private void showError(String message) {
        statusLabel.setText("❌ " + message);
        statusLabel.setStyle("-fx-text-fill: #f44336; -fx-font-size: 11;");
        AlertUtil.showError("Validation Error", message);
    }

    private void clearStatus() {
        statusLabel.setText("");
    }

    // =========================================================================
    // Dialog Management
    // =========================================================================

    private void closeDialog() {
        Stage stage = (Stage) cancelButton.getScene().getWindow();
        stage.close();
    }
}
