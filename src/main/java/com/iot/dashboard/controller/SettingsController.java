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
 * SettingsController — manages the settings dialog.
 *
 * Controls:
 *  - Database URL / username / password
 *  - Sensor mode toggle (Simulation ↔ Real Sensor) — top-right of header
 *  - DHT sensor type (DHT11 / DHT22)
 *  - Serial port + baud rate
 */
public class SettingsController implements Initializable {

    private static final Logger LOGGER = Logger.getLogger(SettingsController.class.getName());

    // ── Database ──────────────────────────────────────────────────────────────
    @FXML private TextField     dbUrlField;
    @FXML private TextField     dbUsernameField;
    @FXML private PasswordField dbPasswordField;

    // ── Sensor mode toggle (header, top-right) ────────────────────────────────
    @FXML private ToggleButton  modeToggleButton;

    // ── DHT type ─────────────────────────────────────────────────────────────
    @FXML private ToggleGroup   dhtToggleGroup;
    @FXML private RadioButton   dht22Radio;
    @FXML private RadioButton   dht11Radio;

    // ── Serial ───────────────────────────────────────────────────────────────
    @FXML private TextField     serialPortField;
    @FXML private TextField     baudRateField;

    // ── Footer ───────────────────────────────────────────────────────────────
    @FXML private Label         statusLabel;
    @FXML private Button        saveButton;
    @FXML private Button        cancelButton;

    // ── Callback ─────────────────────────────────────────────────────────────

    /** Called by DashboardController after settings are saved so it can restart connections. */
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

        // Database
        dbUrlField.setText(config.getDatabaseUrl());
        dbUsernameField.setText(config.getDatabaseUsername());
        dbPasswordField.setText(config.getDatabasePassword());

        // Mode toggle — text and selected state mirror the dashboard toggle
        boolean useReal = config.isUseRealSensor();
        modeToggleButton.setSelected(useReal);
        syncModeToggleLabel(useReal);

        // DHT type
        if ("DHT11".equals(config.getDhtType())) {
            dht11Radio.setSelected(true);
        } else {
            dht22Radio.setSelected(true);   // default DHT22
        }

        // Serial
        serialPortField.setText(config.getSerialPort());
        baudRateField.setText(String.valueOf(config.getBaudRate()));

        clearStatus();
    }

    private void attachEventHandlers() {
        saveButton.setOnAction(event -> handleSave());
        cancelButton.setOnAction(event -> handleCancel());

        // Mode toggle label update (visual only — actual save happens on Save & Apply)
        modeToggleButton.selectedProperty().addListener((obs, oldVal, newVal) -> {
            syncModeToggleLabel(newVal);
            clearStatus();
        });

        // Clear status on any edit
        dbUrlField.setOnKeyTyped(e -> clearStatus());
        dbUsernameField.setOnKeyTyped(e -> clearStatus());
        dbPasswordField.setOnKeyTyped(e -> clearStatus());
        serialPortField.setOnKeyTyped(e -> clearStatus());
        baudRateField.setOnKeyTyped(e -> clearStatus());
    }

    /** Keeps the toggle button label in sync with its selected state. */
    private void syncModeToggleLabel(boolean useReal) {
        if (useReal) {
            modeToggleButton.setText("Real Sensor");
            modeToggleButton.setStyle(
                "-fx-font-size: 11; -fx-padding: 5 14; -fx-cursor: hand;" +
                "-fx-background-radius: 20; -fx-border-radius: 20;" +
                "-fx-border-color: #22c55e; -fx-border-width: 1.5;" +
                "-fx-background-color: #22c55e; -fx-text-fill: white;"
            );
        } else {
            modeToggleButton.setText("Simulation");
            modeToggleButton.setStyle(
                "-fx-font-size: 11; -fx-padding: 5 14; -fx-cursor: hand;" +
                "-fx-background-radius: 20; -fx-border-radius: 20;" +
                "-fx-border-color: #0ea5e9; -fx-border-width: 1.5;" +
                "-fx-background-color: transparent; -fx-text-fill: #0ea5e9;"
            );
        }
    }

    // =========================================================================
    // Save Handler
    // =========================================================================

    private void handleSave() {
        if (!validateInputs()) return;

        ConfigManager config = ConfigManager.getInstance();
        Properties snapshot = config.snapshot();

        try {
            // Database
            config.setDatabaseUrl(dbUrlField.getText().trim());
            config.setDatabaseUsername(dbUsernameField.getText().trim());
            config.setDatabasePassword(dbPasswordField.getText());

            // Sensor mode (from top-right toggle)
            config.setUseRealSensor(modeToggleButton.isSelected());

            // DHT type
            String dhtType = dht11Radio.isSelected() ? "DHT11" : "DHT22";
            config.setDhtType(dhtType);

            // Serial
            config.setSerialPort(serialPortField.getText().trim());
            config.setBaudRate(Integer.parseInt(baudRateField.getText().trim()));

            // Validate DB credentials immediately before persisting
            DatabaseManager.getInstance().refreshDataSource();

            // Persist to ~/.iot-dashboard/config.properties
            config.saveConfig();

            showSuccess("Settings saved.");

            // Notify DashboardController to restart sensor connections
            if (onSettingsSaved != null) {
                onSettingsSaved.run();
            }

            closeDialog();

        } catch (Exception e) {
            // Roll back in-memory state
            config.restore(snapshot);
            try {
                DatabaseManager.getInstance().refreshDataSource();
            } catch (Exception revertError) {
                LOGGER.warning("Failed to restore DB pool after settings error: " + revertError.getMessage());
            }
            showError("Failed to save: " + e.getMessage());
            LOGGER.severe("Settings save failed: " + e);
        }
    }

    private boolean validateInputs() {
        String url          = dbUrlField.getText().trim();
        String username     = dbUsernameField.getText().trim();
        String port         = serialPortField.getText().trim();
        String baudRateStr  = baudRateField.getText().trim();

        if (url.isEmpty()) {
            showError("Database URL cannot be empty.");
            return false;
        }
        if (!url.startsWith("jdbc:mysql://")) {
            showError("Database URL must start with 'jdbc:mysql://'");
            return false;
        }
        if (username.isEmpty()) {
            showError("Database username cannot be empty.");
            return false;
        }

        // Serial port required only when real sensor is selected
        if (modeToggleButton.isSelected() && port.isEmpty()) {
            showError("Serial port cannot be empty when Real Sensor mode is selected.");
            return false;
        }

        if (baudRateStr.isEmpty()) {
            showError("Baud rate cannot be empty.");
            return false;
        }
        try {
            int baud = Integer.parseInt(baudRateStr);
            if (baud < 300 || baud > 921600) {
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
    // Cancel
    // =========================================================================

    private void handleCancel() {
        closeDialog();
    }

    // =========================================================================
    // UI feedback
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
    // Dialog management
    // =========================================================================

    private void closeDialog() {
        Stage stage = (Stage) cancelButton.getScene().getWindow();
        stage.close();
    }
}
