package com.iot.dashboard.model;

/**
 * Enum representing the four sensor metric types simulated by the system.
 * Each entry maps to a row in the sensor_types database table.
 *
 * Gaussian parameters are based on typical real-world energy monitoring values:
 *   Temperature : mean 25°C,    std 2.0  (indoor ambient)
 *   Humidity    : mean 60%,     std 5.0  (relative humidity)
 *   Voltage     : mean 230V,    std 5.0  (European AC grid standard)
 *   ActivePower : mean 1500W,   std 150  (household active power)
 */
public enum SensorType {

    TEMPERATURE (1, "Temperature", "°C",  25.0,  2.0,  10.0, 50.0),
    HUMIDITY    (2, "Humidity",    "%",   60.0,  5.0,  20.0, 100.0),
    VOLTAGE     (3, "Voltage",     "V",  230.0,  5.0, 200.0, 260.0),
    ACTIVE_POWER(4, "Active Power","W",  1500.0,150.0, 200.0,4000.0);

    private final int    typeId;
    private final String measurement;
    private final String unit;
    private final double gaussianMean;
    private final double gaussianStdDev;
    private final double minClamp;   // physical lower bound
    private final double maxClamp;   // physical upper bound

    SensorType(int typeId, String measurement, String unit,
               double gaussianMean, double gaussianStdDev,
               double minClamp, double maxClamp) {
        this.typeId          = typeId;
        this.measurement     = measurement;
        this.unit            = unit;
        this.gaussianMean    = gaussianMean;
        this.gaussianStdDev  = gaussianStdDev;
        this.minClamp        = minClamp;
        this.maxClamp        = maxClamp;
    }

    public int    getTypeId()         { return typeId; }
    public String getMeasurement()    { return measurement; }
    public String getUnit()           { return unit; }
    public double getGaussianMean()   { return gaussianMean; }
    public double getGaussianStdDev() { return gaussianStdDev; }
    public double getMinClamp()       { return minClamp; }
    public double getMaxClamp()       { return maxClamp; }

    /**
     * Returns the display label shown on the dashboard chart.
     */
    public String getChartTitle() {
        return measurement + " (" + unit + ")";
    }

    /**
     * Finds a SensorType by its database typeId.
     */
    public static SensorType fromTypeId(int typeId) {
        for (SensorType st : values()) {
            if (st.typeId == typeId) return st;
        }
        throw new IllegalArgumentException("Unknown typeId: " + typeId);
    }
}
