package com.iot.dashboard.simulation;

import com.iot.dashboard.database.DatabaseManager;
import com.iot.dashboard.model.SensorData;
import com.iot.dashboard.model.SensorType;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SensorSimulator — the core of the IoT simulation engine.
 *
 * Real sensor integration:
 *   Pass excluded sensor types to the constructor to sideline their simulation.
 *   Example: exclude TEMPERATURE and HUMIDITY when RealSensorReader is active,
 *   so only VOLTAGE and ACTIVE_POWER continue to be simulated.
 */
public class SensorSimulator {

    private static final Logger LOGGER = Logger.getLogger(SensorSimulator.class.getName());

    private static final long EMIT_INTERVAL_MS = 1000L;
    private static final int  DEVICE_ID        = 1;

    private final ScheduledExecutorService          scheduler;
    private final ConcurrentLinkedQueue<SensorData> dataQueue;
    private final Consumer<SensorData>              onDataGenerated;
    private final AtomicBoolean                     running;
    private final AtomicLong                        totalGenerated;

    /** Sensor types that will NOT be simulated (handled by real sensor). */
    private final Set<SensorType> excludedTypes;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Default — simulates ALL four sensor types. */
    public SensorSimulator(Consumer<SensorData> onDataGenerated) {
        this(onDataGenerated, EnumSet.noneOf(SensorType.class));
    }

    /**
     * Real-sensor-aware constructor.
     * Excluded types are skipped by the simulator; their data comes from RealSensorReader.
     *
     * Example:
     *   new SensorSimulator(callback, EnumSet.of(SensorType.TEMPERATURE, SensorType.HUMIDITY));
     */
    public SensorSimulator(Consumer<SensorData> onDataGenerated, Set<SensorType> excludedTypes) {
        this.onDataGenerated = onDataGenerated;
        this.excludedTypes   = excludedTypes.isEmpty()
                ? EnumSet.noneOf(SensorType.class)
                : EnumSet.copyOf(excludedTypes);
        this.dataQueue       = new ConcurrentLinkedQueue<>();
        this.running         = new AtomicBoolean(false);
        this.totalGenerated  = new AtomicLong(0);

        ThreadFactory namedFactory = r -> {
            Thread t = new Thread(r, "SensorSimThread-" + Thread.currentThread().threadId());
            t.setDaemon(true);
            return t;
        };

        int activeCount = (int) java.util.Arrays.stream(SensorType.values())
                .filter(t -> !this.excludedTypes.contains(t))
                .count();
        this.scheduler = Executors.newScheduledThreadPool(Math.max(1, activeCount), namedFactory);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void start() {
        if (running.compareAndSet(false, true)) {
            int scheduled = 0;
            for (SensorType type : SensorType.values()) {
                if (excludedTypes.contains(type)) {
                    LOGGER.info("SensorSimulator: skipping " + type.name()
                            + " (handled by real sensor).");
                    continue;
                }
                long initialDelay = type.ordinal() * 250L;
                scheduler.scheduleAtFixedRate(
                        () -> generateAndDispatch(type),
                        initialDelay,
                        EMIT_INTERVAL_MS,
                        TimeUnit.MILLISECONDS
                );
                scheduled++;
            }
            LOGGER.info("SensorSimulator started — " + scheduled + " sensor thread(s) active."
                    + (excludedTypes.isEmpty() ? "" : " Excluded (real sensor): " + excludedTypes));
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    LOGGER.warning("SensorSimulator forced shutdown after timeout.");
                } else {
                    LOGGER.info("SensorSimulator stopped cleanly. Total readings: "
                            + totalGenerated.get());
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning()         { return running.get(); }
    public long    getTotalGenerated() { return totalGenerated.get(); }

    public ConcurrentLinkedQueue<SensorData> getDataQueue() {
        return dataQueue;
    }

    // -------------------------------------------------------------------------
    // Core simulation logic
    // -------------------------------------------------------------------------

    private void generateAndDispatch(SensorType type) {
        if (!running.get()) return;

        try {
            double rawValue = ThreadLocalRandom.current().nextGaussian(
                    type.getGaussianMean(),
                    type.getGaussianStdDev()
            );
            float value = (float) Math.max(type.getMinClamp(),
                                  Math.min(type.getMaxClamp(), rawValue));

            SensorData data = new SensorData(
                    DEVICE_ID,
                    type.getTypeId(),
                    type.getMeasurement(),
                    type.getUnit(),
                    value
            );

            dataQueue.offer(data);

            if (onDataGenerated != null) {
                onDataGenerated.accept(data);
            }

            DatabaseManager.getInstance().insertSensorData(data);
            totalGenerated.incrementAndGet();

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during sensor data generation for " + type.name(), e);
        }
    }
}
