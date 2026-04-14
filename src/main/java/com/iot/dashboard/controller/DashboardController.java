package com.iot.dashboard.controller;

import com.iot.dashboard.DashboardApplication;
import com.iot.dashboard.database.DatabaseManager;
import com.iot.dashboard.model.SensorData;
import com.iot.dashboard.model.SensorType;
import com.iot.dashboard.report.ReportGenerator;
import com.iot.dashboard.simulation.RealSensorReader;
import com.iot.dashboard.simulation.SensorSimulator;
import com.iot.dashboard.util.AlertUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
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
 * REAL SENSOR CONFIGURATION  ←  edit these two constants to enable your sensor
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   USE_REAL_SENSOR  — set true to enable real temperature/humidity sensor.
 *                      When true:
 *                        • RealSensorReader reads from SERIAL_PORT.
 *                        • SensorSimulator skips TEMPERATURE and HUMIDITY.
 *                        • Voltage and Active Power remain simulated.
 *                      When false:
 *                        • All four sensors are simulated (original behaviour).
 *
 *   SERIAL_PORT      — the COM port or tty device your Arduino is connected to.
 *                      Windows : "COM3"  (check Device Manager → Ports)
 *                      Linux   : "/dev/ttyUSB0" or "/dev/ttyACM0"
 *                      macOS   : "/dev/cu.usbmodem14101"
 *
 *   BAUD_RATE        — must match Serial.begin(rate) in your Arduino sketch.
 *                      Default: 9600
 *
 * To find your port, temporarily add this line to initialize():
 *   System.out.println(Arrays.toString(RealSensorReader.listAvailablePorts()));
 *
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class DashboardController implements Initializable {

    private static final Logger LOGGER = Logger.getLogger(DashboardController.class.getName());

    // =========================================================================
    // ★  REAL SENSOR CONFIGURATION — edit here
    // =========================================================================

    /** Set to true to use your physical temperature/humidity sensor. */
    private static final boolean USE_REAL_SENSOR = false;

    /** Serial port your Arduino/sensor board is connected to. */
    private static final String  SERIAL_PORT     = "/dev/ttyUSB0";

    /** Baud rate — must match Serial.begin() in your Arduino sketch. */
    private static final int     BAUD_RATE       = 9600;

    // =========================================================================
    // Constants
    // =========================================================================

    /** Maximum visible data points per chart (60s scrolling window at 1 Hz). */
    private static final int    WINDOW_SIZE = 60;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // =========================================================================
    // FXML Injected Components
    // =========================================================================

    @FXML private Label   titleLabel;
    @FXML private Label   clockLabel;
    @FXML private Label   readingCountLabel;
    @FXML private Button  startStopButton;
    @FXML private Button  reportButton;
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

    @FXML private TableView<SensorData>               dataTable;
    @FXML private TableColumn<SensorData, String>     colSensor;
    @FXML private TableColumn<SensorData, Float>      colValue;
    @FXML private TableColumn<SensorData, String>     colUnit;
    @FXML private TableColumn<SensorData, LocalDateTime> colTimestamp;

    // =========================================================================
    // Internal State
    // =========================================================================

    private SensorSimulator         simulator;
    private RealSensorReader        realSensorReader;
    private ScheduledExecutorService clockScheduler;

    private final Map<SensorType, XYChart.Series<Number, Number>> chartSeriesMap     = new HashMap<>();
    private final Map<SensorType, Label>                           currentValueLabelMap = new HashMap<>();
    private final Map<SensorType, AtomicLong>                      xCounterMap          = new HashMap<>();
    private final ObservableList<SensorData>                       tableData            = FXCollections.observableArrayList();

    private static final int TABLE_MAX_ROWS  = 200;
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
        startAllSources();
    }

    // =========================================================================
    // Source startup — simulator + optional real sensor
    // =========================================================================

    private void startAllSources() {
        if (USE_REAL_SENSOR) {
            startWithRealSensor();
        } else {
            startFullSimulation();
        }
    }

    /**
     * Full simulation mode (USE_REAL_SENSOR = false).
     * All four sensor types are generated by SensorSimulator.
     */
    private void startFullSimulation() {
        simulator = new SensorSimulator(this::onSensorDataReceived);
        simulator.start();
        simulationRunning = true;
        startStopButton.setText("⏸ Pause Simulation");
        updateStatus("Simulation running — all four sensors are simulated.");
    }

    /**
     * Real sensor mode (USE_REAL_SENSOR = true).
     *
     * • RealSensorReader  → Temperature + Humidity (from Arduino via serial)
     * • SensorSimulator   → Voltage + Active Power (still simulated)
     *
     * The Pause/Resume button controls both sources together.
     */
    private void startWithRealSensor() {
        LOGGER.info("Starting in REAL SENSOR mode. Port: " + SERIAL_PORT);

        // 1. Real reader for Temperature and Humidity
        realSensorReader = new RealSensorReader(SERIAL_PORT, BAUD_RATE, this::onSensorDataReceived);
        realSensorReader.start();

        // 2. Simulator for Voltage and Active Power only (Temperature + Humidity excluded)
        simulator = new SensorSimulator(
                this::onSensorDataReceived,
                EnumSet.of(SensorType.TEMPERATURE, SensorType.HUMIDITY)
        );
        simulator.start();

        simulationRunning = true;
        startStopButton.setText("⏸ Pause");
        updateStatus("Real sensor active on " + SERIAL_PORT
                + " | Voltage & Power simulated.");
    }

    // =========================================================================
    // Chart Setup
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

    // =========================================================================
    // Table Setup
    // =========================================================================

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

    // =========================================================================
    // Current Value Labels
    // =========================================================================

    private void setupCurrentValueLabels() {
        currentValueLabelMap.put(SensorType.TEMPERATURE,  tempCurrentLabel);
        currentValueLabelMap.put(SensorType.HUMIDITY,     humidCurrentLabel);
        currentValueLabelMap.put(SensorType.VOLTAGE,      voltCurrentLabel);
        currentValueLabelMap.put(SensorType.ACTIVE_POWER, powerCurrentLabel);
    }

    // =========================================================================
    // Data reception — called from both SensorSimulator and RealSensorReader
    // =========================================================================

    /**
     * Unified data receiver.
     * Called from background threads (simulator threads OR the real sensor reader thread).
     * All UI mutations go through Platform.runLater() to satisfy FXAT constraint.
     */
    private void onSensorDataReceived(SensorData data) {
        Platform.runLater(() -> {
            updateChart(data);
            updateCurrentValueLabel(data);
            addToTable(data);
            updateReadingCount();
        });
    }

    /**
     * Sliding Window chart update.
     * Keeps memory usage constant regardless of runtime duration.
     */
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
        long simCount  = simulator        != null ? simulator.getTotalGenerated()  : 0;
        readingCountLabel.setText("Readings: " + simCount);
    }

    // =========================================================================
    // FXML Button Handlers
    // =========================================================================

    @FXML
    private void handleStartStop(ActionEvent event) {
        if (simulationRunning) {
            // Pause both sources
            if (simulator != null)        simulator.stop();
            if (realSensorReader != null) realSensorReader.stop();
            simulationRunning = false;
            startStopButton.setText("▶ Resume");
            updateStatus(USE_REAL_SENSOR
                    ? "Paused — real sensor and simulation stopped."
                    : "Simulation paused.");
        } else {
            // Resume
            startAllSources();
            updateStatus(USE_REAL_SENSOR
                    ? "Resumed — real sensor active on " + SERIAL_PORT + "."
                    : "Simulation resumed.");
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

    @FXML
    private void handleClearData(ActionEvent event) {
        tableData.clear();
        updateStatus("Table cleared.");
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
        // NOTE: Do NOT close the HikariCP pool here — the pool is shared for the
        // entire JVM lifetime. Closing it on logout prevents re-authentication.
        // Pool shutdown is handled in DashboardApplication.stop() on true app exit.
        LOGGER.info("DashboardController shutdown complete.");
    }

    private void updateStatus(String message) {
        Platform.runLater(() -> {
            if (statusLabel != null) statusLabel.setText("● " + message);
        });
    }
}
