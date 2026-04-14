package com.iot.dashboard.model;

import java.time.LocalDateTime;

/**
 * Data Transfer Object (DTO) representing a single sensor reading.
 * Immutable once created — designed to be passed safely across threads.
 *
 * Sensor types:
 *   1 = Temperature (°C)
 *   2 = Humidity    (%)
 *   3 = Voltage     (V)
 *   4 = Active Power (W)
 */
public class SensorData {

    private final int    deviceId;
    private final int    typeId;
    private final String sensorName;
    private final String unit;
    private final float  value;
    private final LocalDateTime timestamp;

    public SensorData(int deviceId, int typeId, String sensorName, String unit, float value) {
        this.deviceId   = deviceId;
        this.typeId     = typeId;
        this.sensorName = sensorName;
        this.unit       = unit;
        this.value      = value;
        this.timestamp  = LocalDateTime.now();
    }

    // Full constructor (used when reading from DB)
    public SensorData(int deviceId, int typeId, String sensorName, String unit,
                      float value, LocalDateTime timestamp) {
        this.deviceId   = deviceId;
        this.typeId     = typeId;
        this.sensorName = sensorName;
        this.unit       = unit;
        this.value      = value;
        this.timestamp  = timestamp;
    }

    public int          getDeviceId()   { return deviceId; }
    public int          getTypeId()     { return typeId; }
    public String       getSensorName() { return sensorName; }
    public String       getUnit()       { return unit; }
    public float        getValue()      { return value; }
    public LocalDateTime getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("SensorData{device=%d, type=%d, name='%s', value=%.2f %s, time=%s}",
                deviceId, typeId, sensorName, value, unit, timestamp);
    }
}
