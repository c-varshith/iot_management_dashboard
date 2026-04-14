package com.iot.dashboard.controller;

import com.iot.dashboard.DashboardApplication;
import com.iot.dashboard.database.DatabaseManager;
import com.iot.dashboard.model.AdminUser;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.logging.Logger;

/**
 * LoginController — manages the authentication screen.
 *
 * Security notes:
 * - PasswordField masks input on screen.
 * - Authentication runs on a background Task (not FXAT) to prevent UI freeze.
 * - BCrypt comparison (~100ms) would block the UI thread if done synchronously.
 * - Enter key triggers login via onAction binding in FXML.
 * - Failed attempts show a non-specific error message to prevent user enumeration.
 */
public class LoginController implements Initializable {

    private static final Logger LOGGER = Logger.getLogger(LoginController.class.getName());

    @FXML private VBox          loginCard;
    @FXML private TextField     usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button        loginButton;
    @FXML private Label         errorLabel;
    @FXML private Label         statusLabel;
    @FXML private ProgressIndicator loadingSpinner;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        // Animate the login card fading in
        FadeTransition ft = new FadeTransition(Duration.millis(600), loginCard);
        ft.setFromValue(0.0);
        ft.setToValue(1.0);
        ft.play();

        // Clear error when user starts typing
        usernameField.textProperty().addListener((obs, o, n) -> hideError());
        passwordField.textProperty().addListener((obs, o, n) -> hideError());

        // Auto-focus username field
        Platform.runLater(() -> usernameField.requestFocus());

        loadingSpinner.setVisible(false);
        errorLabel.setVisible(false);
    }

    /**
     * Triggered by the Login button or pressing Enter in the password field.
     * Runs BCrypt authentication on a background thread so the UI remains responsive.
     */
    @FXML
    private void handleLogin(ActionEvent event) {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Please enter both username and password.");
            return;
        }

        setLoadingState(true);

        // Run authentication off the FXAT (BCrypt is CPU-intensive ~100ms)
        Task<AdminUser> authTask = new Task<>() {
            @Override
            protected AdminUser call() {
                return DatabaseManager.getInstance().authenticateUser(username, password);
            }
        };

        authTask.setOnSucceeded(e -> {
            AdminUser user = authTask.getValue();
            if (user != null) {
                LOGGER.info("Authenticated: " + user.getUsername());
                statusLabel.setText("Welcome, " + user.getFullName() + "!");
                // Short pause so the success message is visible
                javafx.animation.PauseTransition pause =
                        new javafx.animation.PauseTransition(Duration.millis(500));
                pause.setOnFinished(ev -> navigateToDashboard());
                pause.play();
            } else {
                setLoadingState(false);
                showError("Invalid username or password.");
                passwordField.clear();
                passwordField.requestFocus();
            }
        });

        authTask.setOnFailed(e -> {
            setLoadingState(false);
            showError("Connection error. Check database settings.");
            LOGGER.severe("Auth task failed: " + authTask.getException().getMessage());
        });

        Thread thread = new Thread(authTask);
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void handleExit(ActionEvent event) {
        Platform.exit();
    }

    // ------------------------------------------------------------------

    private void navigateToDashboard() {
        try {
            DashboardApplication.showDashboard();
        } catch (Exception e) {
            showError("Failed to open dashboard: " + e.getMessage());
            LOGGER.severe("Navigation error: " + e.getMessage());
        }
    }

    private void setLoadingState(boolean loading) {
        loginButton.setDisable(loading);
        usernameField.setDisable(loading);
        passwordField.setDisable(loading);
        loadingSpinner.setVisible(loading);
        if (loading) statusLabel.setText("Authenticating...");
        else         statusLabel.setText("");
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        // Shake animation for the card
        javafx.animation.TranslateTransition shake =
                new javafx.animation.TranslateTransition(Duration.millis(60), loginCard);
        shake.setByX(8);
        shake.setAutoReverse(true);
        shake.setCycleCount(4);
        shake.play();
    }

    private void hideError() {
        errorLabel.setVisible(false);
    }
}
