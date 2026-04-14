package com.iot.dashboard;

import com.iot.dashboard.database.DatabaseSetup;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
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

        // Graceful shutdown hook — closes DB pool and running threads on JVM exit
        stage.setOnCloseRequest(event -> {
            LOGGER.info("Application window closed — initiating shutdown.");
            Platform.exit();
        });

        // === Step 1: Database Setup ===
        // Run synchronously on startup before showing the UI.
        // This ensures tables exist and the admin user is seeded before any login attempt.
        boolean dbReady = DatabaseSetup.runSetup();
        if (!dbReady) {
            showDatabaseErrorScreen();
            return;
        }

        // === Step 2: Show Login Screen ===
        try {
            showLoginScreen();
        } catch (Exception e) {
            LOGGER.severe("Failed to load login screen: " + e.getMessage());
            Platform.exit();
        }
    }

    /**
     * Loads and displays the administrator login screen.
     * Called on application start and after logout.
     */
    public static void showLoginScreen() throws Exception {
        FXMLLoader loader = new FXMLLoader(
                DashboardApplication.class.getResource("/com/iot/dashboard/login.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 520, 480);
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
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.ERROR);
        alert.setTitle("Database Connection Failed");
        alert.setHeaderText("Cannot connect to MySQL");
        alert.setContentText(
                "The application could not connect to the MySQL database.\n\n" +
                "Please check:\n" +
                "  1. MySQL server is running (port 3306)\n" +
                "  2. src/main/resources/database.properties has correct credentials\n" +
                "  3. The 'iot_dashboard' database has been created\n" +
                "     (run setup.sql in MySQL Workbench)\n\n" +
                "Default config: localhost:3306 / root / your_password_here");
        alert.showAndWait();
        Platform.exit();
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
