package com.iot.dashboard;

import com.iot.dashboard.database.DatabaseSetup;
import com.iot.dashboard.util.ConfigManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.util.logging.Logger;

/**
 * DashboardApplication — primary JavaFX entry point.
 *
 * Orchestrates:
 *  1. Database initialisation (creates tables, seeds admin user if needed).
 *  2. Login screen presentation.
 *  3. Dashboard screen transition after successful authentication.
 *  4. Graceful JVM shutdown.
 *
 * The MVC lifecycle:
 *  start() → showLoginScreen() → [user authenticates] → showDashboard()
 */
public class DashboardApplication extends Application {

    private static final Logger LOGGER = Logger.getLogger(DashboardApplication.class.getName());

    /** Reference to the primary stage — used for screen transitions. */
    private static Stage primaryStage;

    @Override
    public void start(Stage stage) {
        primaryStage = stage;

        stage.setOnCloseRequest(event -> {
            LOGGER.info("Application window closed — initiating shutdown.");
            Platform.exit();
        });

        ConfigManager config = ConfigManager.getInstance();

        // === Step 1: Create config directory + starter file if missing ===
        // This guarantees users on Windows/Linux can always find the file
        // at %USERPROFILE%\.iot-dashboard\config.properties the moment they
        // launch the app for the first time.
        try {
            config.createConfigIfMissing();
        } catch (Exception e) {
            LOGGER.warning("Could not create starter config: " + e.getMessage());
        }

        // === Step 2: First-Run check ===
        // If the DB password is blank (embedded default, no external config set),
        // show the setup wizard so the user can enter credentials before we attempt
        // any connection. This avoids the silent SQL crash on first launch.
        boolean passwordBlank = config.getDatabasePassword().isBlank();
        if (passwordBlank) {
            try {
                showFirstRunSetup();
            } catch (Exception e) {
                LOGGER.severe("Failed to load first-run setup screen: " + e.getMessage());
                Platform.exit();
            }
            return; // First-run wizard handles DB setup + login transition
        }

        // === Step 3: Normal launch — DB setup then login ===
        boolean dbReady = DatabaseSetup.runSetup();
        if (!dbReady) {
            showDatabaseErrorScreen();
            return;
        }

        try {
            showLoginScreen();
        } catch (Exception e) {
            LOGGER.severe("Failed to load login screen: " + e.getMessage());
            Platform.exit();
        }
    }

    /**
     * Shows the first-run setup dialog where the user enters DB credentials.
     * After saving, the wizard transitions directly to the login screen.
     */
    public static void showFirstRunSetup() throws Exception {
        javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                DashboardApplication.class.getResource("/com/iot/dashboard/firstrun.fxml"));
        javafx.scene.Parent root = loader.load();

        javafx.scene.Scene scene = new javafx.scene.Scene(root, 480, 420);
        scene.getStylesheets().add(
                DashboardApplication.class.getResource("/com/iot/dashboard/styles.css")
                        .toExternalForm());

        primaryStage.setTitle("IoT Dashboard — First Time Setup");
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.centerOnScreen();
        primaryStage.show();
    }

    /**
     * Loads and displays the administrator login screen.
     * Called on application start and after logout.
     */
    public static void showLoginScreen() throws Exception {
        FXMLLoader loader = new FXMLLoader(
                DashboardApplication.class.getResource("/com/iot/dashboard/login.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 520, 510);
        scene.getStylesheets().add(
                DashboardApplication.class.getResource("/com/iot/dashboard/styles.css")
                        .toExternalForm());

        primaryStage.setTitle("IoT Smart Energy Dashboard — Login");
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.centerOnScreen();
        primaryStage.show();
    }

    /**
     * Loads and displays the main dashboard interface.
     * Called by LoginController after successful BCrypt authentication.
     */
    public static void showDashboard() throws Exception {
        FXMLLoader loader = new FXMLLoader(
                DashboardApplication.class.getResource("/com/iot/dashboard/dashboard.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 1400, 900);
        scene.getStylesheets().add(
                DashboardApplication.class.getResource("/com/iot/dashboard/styles.css")
                        .toExternalForm());

        primaryStage.setTitle("IoT Smart Energy Management Dashboard");
        primaryStage.setScene(scene);
        primaryStage.setResizable(true);
        primaryStage.setMinWidth(1100);
        primaryStage.setMinHeight(700);
        primaryStage.centerOnScreen();
        primaryStage.show();
    }

    /**
     * Displayed when the MySQL connection fails on startup.
     * Prompts user to check database.properties.
     */
    private void showDatabaseErrorScreen() {
        ConfigManager config = ConfigManager.getInstance();
        String configPath    = config.getConfigPath().toString();

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Database Connection Failed");
        alert.setHeaderText("Cannot connect to MySQL");
        alert.setContentText(
                "The application could not connect to the MySQL database.\n\n" +
                "Edit your config file and set the correct password:\n" +
                "  " + configPath + "\n\n" +
                "Make sure:\n" +
                "  • MySQL is running on port 3306\n" +
                "  • The 'iot_dashboard' database exists\n" +
                "  • db.username and db.password are correct\n\n" +
                "Then restart the application.");

        ButtonType setupAgain = new ButtonType("Open Setup Wizard");
        ButtonType exit       = new ButtonType("Exit", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(setupAgain, exit);

        alert.showAndWait().ifPresent(choice -> {
            if (choice == setupAgain) {
                try {
                    // Clear password so first-run check triggers again
                    config.setDatabasePassword("");
                    showFirstRunSetup();
                } catch (Exception ex) {
                    LOGGER.severe("Failed to reopen setup wizard: " + ex.getMessage());
                    Platform.exit();
                }
            } else {
                Platform.exit();
            }
        });
    }

    /**
     * Called by the JavaFX runtime on true application exit (window close or Platform.exit()).
     * This is the correct place to shut down the HikariCP pool — NOT on logout.
     */
    @Override
    public void stop() {
        com.iot.dashboard.database.DatabaseManager.getInstance().shutdown();
        LOGGER.info("Application stopped — HikariCP pool closed.");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
