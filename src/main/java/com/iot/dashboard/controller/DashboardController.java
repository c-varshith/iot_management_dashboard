package com.iot.dashboard.controller;

import com.iot.dashboard.DashboardApplication;
import com.iot.dashboard.database.DatabaseManager;
import com.iot.dashboard.model.SensorData;
import com.iot.dashboard.model.SensorType;
import com.iot.dashboard.report.ReportGenerator;
import com.iot.dashboard.simulation.RealSensorReader;
import com.iot.dashboard.simulation.SensorSimulator;
import com.iot.dashboard.util.AlertUtil;
import com.iot.dashboard.util.ConfigManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DashboardController — central MVC controller.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CONFIGURATION  ←  Now managed via Settings UI and ConfigManager
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Users can configure:
 *   • Database credentials (URL, username, password)
 *   • Sensor mode (real sensor vs simulation)
 *   • Serial port and baud rate
 *
 * Settings are saved to ~/.iot-dashboard/config.properties
 *
 * CRUD operations exposed via this controller:
 *   CREATE — sensor readings inserted on every tick (insertSensorData)
 *   READ   — live charts, data table, alert count label (SELECT queries + JOIN)
 *   UPDATE — "Resolve All Alerts" button → resolveAlert() / updateDeviceStatus()
 *   DELETE — "Clear Old Data" button     → deleteOldReadings() (actual DB delete)
 */
public class DashboardController implements Initializable {

    private static final Logger LOGGER = Logger.getLogger(DashboardController.class.getName());

    // =========================================================================
    // Constants
    // =========================================================================
    private static final int    WINDOW_SIZE = 60;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // =========================================================================
    // FXML Injected Components
    // =========================================================================
    @FXML private Label   titleLabel;
    @FXML private Label   clockLabel;
    @FXML private Label   readingCountLabel;
    @FXML private Label   alertCountLabel;      // NEW — shows active alert count
    @FXML private Button  startStopButton;
    @FXML private Button  reportButton;
    @FXML private Button  settingsButton;
    @FXML private Button  logoutButton;

    @FXML private LineChart<Number, Number> tempChart;
    @FXML private LineChart<Number, Number> humidChart;
    @FXML private LineChart<Number, Number> voltChart;
    @FXML private LineChart<Number, Number> powerChart;

    @FXML private Label tempCurrentLabel;
    @FXML private Label humidCurrentLabel;
    @FXML private Label voltCurrentLabel;
    @FXML private Label powerCurrentLabel;

    @FXML private Label statusLabel;
    @FXML private Label modeStatusLabel;

    @FXML private TableView<SensorData>               dataTable;
    @FXML private TableColumn<SensorData, String>     colSensor;
    @FXML private TableColumn<SensorData, Float>      colValue;
    @FXML private TableColumn<SensorData, String>     colUnit;
    @FXML private TableColumn<SensorData, LocalDateTime> colTimestamp;

    // =========================================================================
    // Internal State
    // =========================================================================
    private SensorSimulator          simulator;
    private RealSensorReader         realSensorReader;
    private ScheduledExecutorService clockScheduler;
    private ScheduledExecutorService alertScheduler;   // NEW — polls alert count

    private final Map<SensorType, XYChart.Series<Number, Number>> chartSeriesMap      = new HashMap<>();
    private final Map<SensorType, Label>                           currentValueLabelMap = new HashMap<>();
    private final Map<SensorType, AtomicLong>                      xCounterMap          = new HashMap<>();
    private final ObservableList<SensorData>                       tableData            = FXCollections.observableArrayList();

    private static final int TABLE_MAX_ROWS   = 200;
    private              boolean simulationRunning = false;

    // =========================================================================
    // Initialisation
    // =========================================================================
    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        setupCharts();
        setupTable();
        setupCurrentValueLabels();
        startClock();
        startAlertPoller();   // NEW — periodically refresh alert count from DB
        startAllSources();
    }

    // =========================================================================
    // Source startup
    // =========================================================================
    private void startAllSources() {
        ConfigManager config = ConfigManager.getInstance();
        syncModeControls(config.isUseRealSensor());
        if (config.isUseRealSensor()) startWithRealSensor();
        else                           startFullSimulation();
    }

    private void startFullSimulation() {
        simulator = new SensorSimulator(this::onSensorDataReceived);
        simulator.start();
        simulationRunning = true;
        startStopButton.setText("⏸ Pause Sensors");
        updateStatus("Simulation running — all four sensors are simulated.");
    }

    private void startWithRealSensor() {
        ConfigManager config = ConfigManager.getInstance();
        String serialPort = config.getSerialPort();
        int    baudRate   = config.getBaudRate();
        String dhtType    = config.getDhtType();

        LOGGER.info("Starting in REAL SENSOR mode. Port: " + serialPort + " @ " + baudRate + " baud, DHT: " + dhtType);
        realSensorReader = new RealSensorReader(serialPort, baudRate, dhtType, this::onSensorDataReceived);
        realSensorReader.start();

        simulator = new SensorSimulator(
                this::onSensorDataReceived,
                EnumSet.of(SensorType.TEMPERATURE, SensorType.HUMIDITY)
        );
        simulator.start();

        simulationRunning = true;
        startStopButton.setText("⏸ Pause Sensors");
        updateStatus("Real sensor active on " + serialPort + ".");
    }

    // =========================================================================
    // Alert Poller — READ alerts from DB every 5 seconds
    // =========================================================================

    /**
     * Polls the alerts table every 5 seconds and refreshes the alert count label.
     * This demonstrates a live READ query against the alerts table (populated
     * automatically by the trg_high_sensor_alert trigger).
     */
    private void startAlertPoller() {
        alertScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AlertPollerThread");
            t.setDaemon(true);
            return t;
        });
        alertScheduler.scheduleAtFixedRate(() -> {
            try {
                int count = DatabaseManager.getInstance().getActiveAlertCount();
                Platform.runLater(() -> {
                    if (alertCountLabel != null) {
                        alertCountLabel.setText(count > 0
                            ? "🔔 Alerts: " + count
                            : "✅ No Alerts");
                        alertCountLabel.setStyle(count > 0
                            ? "-fx-text-fill: #ff6b6b;"
                            : "-fx-text-fill: #51cf66;");
                    }
                });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Alert poll failed", e);
            }
        }, 2, 5, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Chart / Table Setup
    // =========================================================================
    private void setupCharts() {
        setupSingleChart(tempChart,  SensorType.TEMPERATURE,  "Temperature (°C)", 10,  50);
        setupSingleChart(humidChart, SensorType.HUMIDITY,     "Humidity (%)",     20,  100);
        setupSingleChart(voltChart,  SensorType.VOLTAGE,      "Voltage (V)",      200, 260);
        setupSingleChart(powerChart, SensorType.ACTIVE_POWER, "Active Power (W)", 0,   4000);
    }

    private void setupSingleChart(LineChart<Number, Number> chart, SensorType type,
                                  String seriesName, double yMin, double yMax) {
        NumberAxis yAxis = (NumberAxis) chart.getYAxis();
        yAxis.setAutoRanging(false);
        yAxis.setLowerBound(yMin);
        yAxis.setUpperBound(yMax);
        yAxis.setTickUnit((yMax - yMin) / 5.0);

        NumberAxis xAxis = (NumberAxis) chart.getXAxis();
        xAxis.setAutoRanging(true);
        xAxis.setLabel("Time (s)");

        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(seriesName);
        chart.getData().add(series);

        chartSeriesMap.put(type, series);
        xCounterMap.put(type, new AtomicLong(0));
    }

    private void setupTable() {
        colSensor.setCellValueFactory(new PropertyValueFactory<>("sensorName"));
        colValue.setCellValueFactory(new PropertyValueFactory<>("value"));
        colUnit.setCellValueFactory(new PropertyValueFactory<>("unit"));
        colTimestamp.setCellValueFactory(new PropertyValueFactory<>("timestamp"));

        colTimestamp.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(LocalDateTime item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText(item.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
        });

        colValue.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Float item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText(String.format("%.2f", item));
            }
        });

        dataTable.setItems(tableData);
        dataTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }

    private void setupCurrentValueLabels() {
        currentValueLabelMap.put(SensorType.TEMPERATURE,  tempCurrentLabel);
        currentValueLabelMap.put(SensorType.HUMIDITY,     humidCurrentLabel);
        currentValueLabelMap.put(SensorType.VOLTAGE,      voltCurrentLabel);
        currentValueLabelMap.put(SensorType.ACTIVE_POWER, powerCurrentLabel);
    }

    private void syncModeControls(boolean useRealSensor) {
        if (modeStatusLabel != null) {
            modeStatusLabel.setText(useRealSensor
                    ? "Mode: real sensor + simulation fallback"
                    : "Mode: full simulation");
        }
    }

    private void stopCurrentSources() {
        if (simulator != null && simulator.isRunning()) {
            simulator.stop();
        }
        if (realSensorReader != null && realSensorReader.isRunning()) {
            realSensorReader.stop();
        }
        simulationRunning = false;
    }

    // =========================================================================
    // Data reception
    // =========================================================================
    private void onSensorDataReceived(SensorData data) {
        Platform.runLater(() -> {
            updateChart(data);
            updateCurrentValueLabel(data);
            addToTable(data);
            updateReadingCount();
        });
    }

    private void updateChart(SensorData data) {
        SensorType type = SensorType.fromTypeId(data.getTypeId());
        XYChart.Series<Number, Number> series = chartSeriesMap.get(type);
        if (series == null) return;

        long x = xCounterMap.get(type).incrementAndGet();
        series.getData().add(new XYChart.Data<>(x, data.getValue()));

        if (series.getData().size() > WINDOW_SIZE) {
            series.getData().remove(0);
        }
    }

    private void updateCurrentValueLabel(SensorData data) {
        SensorType type = SensorType.fromTypeId(data.getTypeId());
        Label lbl = currentValueLabelMap.get(type);
        if (lbl != null) {
            lbl.setText(String.format("%.2f %s", data.getValue(), data.getUnit()));
        }
    }

    private void addToTable(SensorData data) {
        tableData.add(0, data);
        if (tableData.size() > TABLE_MAX_ROWS) {
            tableData.remove(TABLE_MAX_ROWS, tableData.size());
        }
    }

    private void updateReadingCount() {
        long simCount = simulator != null ? simulator.getTotalGenerated() : 0;
        readingCountLabel.setText("Readings: " + simCount);
    }

    // =========================================================================
    // FXML Button Handlers
    // =========================================================================

    @FXML
    private void handleStartStop(ActionEvent event) {
        if (simulationRunning) {
            stopCurrentSources();
            startStopButton.setText("▶ Resume");
            ConfigManager config = ConfigManager.getInstance();
            updateStatus(config.isUseRealSensor()
                    ? "Paused — real sensor and simulation stopped."
                    : "Simulation paused.");
        } else {
            startAllSources();
            ConfigManager config = ConfigManager.getInstance();
            updateStatus(config.isUseRealSensor()
                    ? "Resumed — real sensor active on " + config.getSerialPort() + "."
                    : "Simulation resumed.");
        }
    }

    @FXML
    private void handleSettings(ActionEvent event) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    DashboardApplication.class.getResource("/com/iot/dashboard/settings.fxml"));
            Parent root = loader.load();

            SettingsController controller = loader.getController();
            controller.setOnSettingsSaved(this::restartConnections);

            Stage settingsStage = new Stage();
            settingsStage.setTitle("Application Settings");
            settingsStage.setScene(new Scene(root, 500, 600));
            settingsStage.initModality(Modality.APPLICATION_MODAL);
            settingsStage.setResizable(false);
            settingsStage.centerOnScreen();
            settingsStage.showAndWait();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to open settings dialog", e);
            AlertUtil.showError("Settings Error", "Failed to open settings: " + e.getMessage());
        }
    }

    /**
     * Restarts sensor connections after settings have been saved.
     * Called when user saves settings and closes the settings dialog.
     */
    private void restartConnections() {
        restartConnections(simulationRunning);
    }

    private void restartConnections(boolean shouldResume) {
        try {
            // Stop current connections
            stopCurrentSources();

            // Reload configuration
            ConfigManager.getInstance().reload();
            DatabaseManager.getInstance().refreshDataSource();

            // Restart with new configuration
            syncModeControls(ConfigManager.getInstance().isUseRealSensor());
            if (shouldResume) {
                startAllSources();
                updateStatus("✓ Settings applied. Sensor connections restarted.");
            } else {
                startStopButton.setText("▶ Resume");
                updateStatus("✓ Settings applied. Sensor connections are paused.");
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to restart connections", e);
            AlertUtil.showError("Restart Error", "Failed to restart connections: " + e.getMessage());
            updateStatus("✗ Failed to restart connections. Check logs.");
        }
    }

    @FXML
    private void handleGenerateReport(ActionEvent event) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save IoT Energy Report");
        fileChooser.setInitialFileName("iot_energy_report_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + ".pdf");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF Documents", "*.pdf"));

        File selectedFile = fileChooser.showSaveDialog(reportButton.getScene().getWindow());
        if (selectedFile == null) return;

        updateStatus("Generating PDF report...");
        reportButton.setDisable(true);

        Thread reportThread = new Thread(() -> {
            try {
                LocalDateTime from = LocalDateTime.now().minusHours(1);
                LocalDateTime to   = LocalDateTime.now();
                ReportGenerator.generatePdfReport(selectedFile.getAbsolutePath(), from, to);

                Platform.runLater(() -> {
                    reportButton.setDisable(false);
                    updateStatus("Report saved: " + selectedFile.getName());
                    AlertUtil.showInfo("Report Generated",
                            "PDF report saved successfully:\n" + selectedFile.getAbsolutePath());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    reportButton.setDisable(false);
                    updateStatus("Report generation failed.");
                    AlertUtil.showError("Report Error",
                            "Failed to generate report: " + e.getMessage());
                    LOGGER.log(Level.SEVERE, "Report generation failed", e);
                });
            }
        });
        reportThread.setDaemon(true);
        reportThread.start();
    }

    @FXML
    private void handleLogout(ActionEvent event) {
        boolean confirm = AlertUtil.showConfirmation(
                "Logout", "Stop all sensors and return to login screen?");
        if (!confirm) return;
        shutdown();
        try {
            DashboardApplication.showLoginScreen();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to navigate to login screen", e);
        }
    }

    /**
     * Clears the on-screen table AND permanently deletes readings from the
     * database that are older than 24 hours.
     *
     * This is the DELETE CRUD operation — it executes:
     *   DELETE FROM sensor_data WHERE timestamp < NOW() - INTERVAL 24 HOUR
     *
     * It also calls the GetSensorStats stored procedure to log a final
     * summary before purging.
     */
    @FXML
    private void handleClearData(ActionEvent event) {
        boolean confirm = AlertUtil.showConfirmation(
                "Clear Old Data",
                "This will:\n" +
                "  • Clear the on-screen readings table\n" +
                "  • Delete all DB readings older than 24 hours\n\n" +
                "Recent readings are kept. Continue?");

        if (!confirm) return;

        // Log stats via stored procedure before deleting (READ via SP)
        String tempStats  = DatabaseManager.getInstance().callGetSensorStats(1, 24);
        String powerStats = DatabaseManager.getInstance().callGetSensorStats(4, 24);
        LOGGER.info("Pre-delete stats — " + tempStats);
        LOGGER.info("Pre-delete stats — " + powerStats);

        // Nested subquery: find devices that currently have active alerts (Chapter 6)
        List<String[]> alertingDevices = DatabaseManager.getInstance().getDevicesWithActiveAlerts();
        LOGGER.info("Devices with active alerts before purge: " + alertingDevices.size());

        // DELETE old readings from the database
        int deleted = DatabaseManager.getInstance().deleteOldReadings(24);

        // Clear the UI table
        tableData.clear();

        updateStatus("Deleted " + deleted + " old DB reading(s). UI table cleared.");
        AlertUtil.showInfo("Data Cleared",
                "Deleted " + deleted + " sensor reading(s) older than 24 h from the database.\n\n" +
                "Temperature stats (last 24 h):\n" + tempStats);
    }

    /**
     * Resolves all active alerts in the database.
     *
     * This is the UPDATE CRUD operation — for each active alert it executes:
     *   UPDATE alerts SET is_resolved = 1 WHERE alert_id = ?
     *
     * Bound to the "Resolve Alerts" button in dashboard.fxml.
     */
    @FXML
    private void handleResolveAlerts(ActionEvent event) {
        // Uses the vw_active_alerts VIEW (Advanced SQL — Chapter 6)
        List<String[]> activeAlerts =
                DatabaseManager.getInstance().getActiveAlertsFromView(200);

        if (activeAlerts.isEmpty()) {
            AlertUtil.showInfo("No Active Alerts", "There are no active alerts to resolve.");
            return;
        }

        boolean confirm = AlertUtil.showConfirmation(
                "Resolve Alerts",
                "Mark all " + activeAlerts.size() + " active alert(s) as resolved?");
        if (!confirm) return;

        int resolved = 0;
        for (String[] alert : activeAlerts) {
            int alertId = Integer.parseInt(alert[0]);
            if (DatabaseManager.getInstance().resolveAlert(alertId)) resolved++;
        }

        // Clean up resolved alerts from DB (DELETE)
        int deleted = DatabaseManager.getInstance().deleteResolvedAlerts();

        updateStatus("Resolved " + resolved + " alert(s). Cleaned up " + deleted + " record(s).");
        AlertUtil.showInfo("Alerts Resolved",
                resolved + " alert(s) resolved and removed from the database.");
    }

    // =========================================================================
    // Clock
    // =========================================================================
    private void startClock() {
        clockScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ClockThread");
            t.setDaemon(true);
            return t;
        });
        clockScheduler.scheduleAtFixedRate(() -> {
            String time = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm:ss"));
            Platform.runLater(() -> clockLabel.setText(time));
        }, 0, 1, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================
    public void shutdown() {
        if (simulator        != null && simulator.isRunning())        simulator.stop();
        if (realSensorReader != null && realSensorReader.isRunning()) realSensorReader.stop();
        if (clockScheduler   != null) clockScheduler.shutdownNow();
        if (alertScheduler   != null) alertScheduler.shutdownNow();
        LOGGER.info("DashboardController shutdown complete.");
    }

    private void updateStatus(String message) {
        Platform.runLater(() -> {
            if (statusLabel != null) statusLabel.setText("● " + message);
        });
    }
}
