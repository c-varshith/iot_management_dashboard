package com.iot.dashboard.simulation;

import com.fazecast.jSerialComm.SerialPort;
import com.iot.dashboard.model.SensorData;
import com.iot.dashboard.model.SensorType;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RealSensorReader — reads live Temperature and Humidity from a physical sensor
 * connected via USB serial (e.g., Arduino + DHT11 or DHT22).
 *
 * Expected serial protocol (one line per cycle, sent every ~1 second):
 *   T:25.30,H:60.10
 *
 *   T = temperature in °C
 *   H = relative humidity in %
 *
 * How it works:
 *   1. Opens the configured COM/tty port at the specified baud rate.
 *   2. A daemon thread reads lines in a blocking loop.
 *   3. Each valid line is parsed into two SensorData objects (temp + humidity).
 *   4. The same Consumer<SensorData> callback used by SensorSimulator is fired,
 *      so DashboardController receives real data exactly like simulated data.
 *
 * Usage:
 *   RealSensorReader reader = new RealSensorReader("COM3", 9600, this::onSensorDataReceived);
 *   reader.start();
 *   // ... later:
 *   reader.stop();
 *
 * To find your port name:
 *   Windows : COM3, COM4, ... (check Device Manager → Ports)
 *   Linux   : /dev/ttyUSB0, /dev/ttyACM0
 *   macOS   : /dev/cu.usbmodem14101
 *
 *   Or call RealSensorReader.listAvailablePorts() at startup to print all detected ports.
 */
public class RealSensorReader {

    private static final Logger LOGGER = Logger.getLogger(RealSensorReader.class.getName());

    private final String               portName;
    private final int                  baudRate;
    private final Consumer<SensorData> onDataReceived;
    private final AtomicBoolean        running = new AtomicBoolean(false);

    private SerialPort serialPort;
    private Thread     readerThread;

    /**
     * @param portName       Serial port name, e.g. "COM3" or "/dev/ttyUSB0"
     * @param baudRate       Must match Arduino Serial.begin(rate) — typically 9600
     * @param onDataReceived Callback fired for each parsed Temperature and Humidity reading.
     *                       Called from the reader thread — do NOT update JavaFX nodes directly.
     *                       Wrap updates in Platform.runLater() (already done in DashboardController).
     */
    public RealSensorReader(String portName, int baudRate, Consumer<SensorData> onDataReceived) {
        this.portName       = portName;
        this.baudRate       = baudRate;
        this.onDataReceived = onDataReceived;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /** Starts the background reader thread. Safe to call only once. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            readerThread = new Thread(this::readLoop, "RealSensorThread");
            readerThread.setDaemon(true); // JVM can exit even if this thread is still alive
            readerThread.start();
            LOGGER.info("RealSensorReader starting on port: " + portName + " @ " + baudRate + " baud");
        }
    }

    /** Stops the reader and closes the serial port. */
    public void stop() {
        running.set(false);
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            LOGGER.info("RealSensorReader: serial port closed.");
        }
    }

    public boolean isRunning() { return running.get(); }

    // =========================================================================
    // Serial read loop
    // =========================================================================

    private void readLoop() {
        serialPort = SerialPort.getCommPort(portName);
        serialPort.setBaudRate(baudRate);
        // TIMEOUT_READ_SEMI_BLOCKING: blocks until at least 1 byte arrives, or timeout.
        // Must be comfortably above the Arduino READ_INTERVAL_MS (2000ms) so we never
        // race — 5000ms gives 2.5× headroom, plus room for USB enumeration delay on boot.
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 5000, 0);

        if (!serialPort.openPort()) {
            LOGGER.severe(
                "RealSensorReader: could not open port '" + portName + "'.\n" +
                "Available ports: " + String.join(", ", listAvailablePorts()) + "\n" +
                "Check your SERIAL_PORT constant in DashboardController."
            );
            running.set(false);
            return;
        }

        LOGGER.info("RealSensorReader: port open — waiting for sensor data...");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(serialPort.getInputStream()))) {

            while (running.get()) {
                try {
                    String line = reader.readLine();
                    if (line != null) parseLine(line.trim());
                } catch (com.fazecast.jSerialComm.SerialPortTimeoutException e) {
                    // No data arrived within the timeout window.
                    // Normal during Arduino boot or between readings — just retry.
                    LOGGER.fine("RealSensorReader: read timeout — retrying...");
                }
            }

        } catch (Exception e) {
            if (running.get()) {
                // Unexpected error (port unplugged, I/O failure) — log and exit.
                LOGGER.log(Level.WARNING, "RealSensorReader: serial read error", e);
            }
        } finally {
            if (serialPort.isOpen()) serialPort.closePort();
        }
    }

    // =========================================================================
    // Line parser
    // =========================================================================

    /**
     * Parses one line from the Arduino in the format: T:25.30,H:60.10
     *
     * Fires two SensorData callbacks — one for Temperature, one for Humidity.
     * Silently skips malformed lines (e.g. startup garbage, partial reads).
     */
    private void parseLine(String line) {
        if (line.isEmpty() || !line.startsWith("T:")) return;

        try {
            // Split "T:25.30,H:60.10" → ["T:25.30", "H:60.10"]
            String[] parts = line.split(",");
            if (parts.length < 2) return;

            float temp  = Float.parseFloat(parts[0].split(":")[1]);
            float humid = Float.parseFloat(parts[1].split(":")[1]);

            // Clamp to the same physical bounds defined in SensorType
            temp  = Math.max(10f, Math.min(50f,  temp));
            humid = Math.max(20f, Math.min(100f, humid));

            SensorData tempData = new SensorData(
                    1,
                    SensorType.TEMPERATURE.getTypeId(),
                    SensorType.TEMPERATURE.getMeasurement(),
                    SensorType.TEMPERATURE.getUnit(),
                    temp
            );

            SensorData humidData = new SensorData(
                    1,
                    SensorType.HUMIDITY.getTypeId(),
                    SensorType.HUMIDITY.getMeasurement(),
                    SensorType.HUMIDITY.getUnit(),
                    humid
            );

            if (onDataReceived != null) {
                onDataReceived.accept(tempData);
                onDataReceived.accept(humidData);
            }

        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            LOGGER.warning("RealSensorReader: skipping malformed line: '" + line + "'");
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /**
     * Returns names of all serial ports detected on this machine.
     * Call at startup to identify the correct port for your Arduino.
     *
     * Example (add to DashboardApplication.main or LoginController.initialize):
     *   System.out.println("Ports: " + Arrays.toString(RealSensorReader.listAvailablePorts()));
     */
    public static String[] listAvailablePorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] names = new String[ports.length];
        for (int i = 0; i < ports.length; i++) {
            names[i] = ports[i].getSystemPortName() + " (" + ports[i].getDescriptivePortName() + ")";
        }
        return names;
    }
}
