/**
 * arduino_dht_sensor.ino
 *
 * Reads temperature and humidity from a DHT11 or DHT22 sensor and
 * transmits the data over USB serial in the format expected by RealSensorReader:
 *
 *   T:25.30,H:60.10
 *
 * Sensor type can be set two ways:
 *   1. Change DHTTYPE below and re-flash — compile-time selection.
 *   2. Send "TYPE:DHT11\n" or "TYPE:DHT22\n" over serial at runtime — the
 *      app does this automatically whenever it connects using the value saved
 *      in Settings.  The sketch re-initialises the sensor without a reflash.
 *
 * Wiring (applies to both DHT11 and DHT22):
 *   Pin 1 (VCC) → 3.3 V or 5 V
 *   Pin 2 (DATA) → Arduino digital pin 7  (change DHTPIN if needed)
 *   Pin 3 (NC)  → not connected
 *   Pin 4 (GND) → GND
 *   10 kΩ pull-up resistor between VCC and DATA (required for DHT11, optional for DHT22 modules with on-board resistor)
 */

#include <DHT.h>

// ── Compile-time defaults ─────────────────────────────────────────────────────
#define DHTPIN           7     // Digital pin the DHT DATA line is wired to
#define DEFAULT_DHTTYPE  DHT22 // Change to DHT11 if you have the smaller blue sensor
#define READ_INTERVAL_MS 2000  // Must be ≥ 1000 ms for DHT11; DHT22 needs ≥ 2000 ms
#define SERIAL_CMD_BUF   32    // Max bytes in an incoming command line

// ── Runtime state ─────────────────────────────────────────────────────────────
DHT   dht(DHTPIN, DEFAULT_DHTTYPE);
int   currentDhtType = DEFAULT_DHTTYPE; // tracks runtime selection
char  cmdBuf[SERIAL_CMD_BUF];
int   cmdPos = 0;

// ── Setup ─────────────────────────────────────────────────────────────────────
void setup()
{
  Serial.begin(9600);
  dht.begin();
  delay(2000);
  Serial.println("# IoT Dashboard Sensor Ready (DHT22)");
}

// ── Main loop ─────────────────────────────────────────────────────────────────
void loop()
{
  // Check for incoming commands from the Java app before reading the sensor
  readSerialCommands();

  delay(READ_INTERVAL_MS);

  float humidity    = dht.readHumidity();
  float temperature = dht.readTemperature(); // Celsius

  if (isnan(humidity) || isnan(temperature))
  {
    Serial.println("# ERROR: Failed to read from DHT sensor — check wiring and sensor type.");
    return;
  }

  Serial.print("T:");
  Serial.print(temperature, 2);
  Serial.print(",H:");
  Serial.println(humidity, 2);
}

// ── Serial command handler ────────────────────────────────────────────────────
/**
 * Reads incoming serial bytes into a line buffer.
 * When a newline is found, dispatches the complete command.
 *
 * Supported commands:
 *   TYPE:DHT11  — switch to DHT11 mode and reinitialise
 *   TYPE:DHT22  — switch to DHT22 mode and reinitialise
 */
void readSerialCommands()
{
  while (Serial.available() > 0)
  {
    char c = (char)Serial.read();

    if (c == '\r') continue; // ignore CR in CRLF line endings

    if (c == '\n')
    {
      cmdBuf[cmdPos] = '\0';
      handleCommand(cmdBuf);
      cmdPos = 0;
      return;
    }

    if (cmdPos < SERIAL_CMD_BUF - 1)
    {
      cmdBuf[cmdPos++] = c;
    }
    // Silently drop bytes beyond buffer — avoids overflow
  }
}

void handleCommand(const char* cmd)
{
  if (strncmp(cmd, "TYPE:", 5) == 0)
  {
    const char* typeStr = cmd + 5;

    if (strcmp(typeStr, "DHT11") == 0 && currentDhtType != DHT11)
    {
      currentDhtType = DHT11;
      dht = DHT(DHTPIN, DHT11);
      dht.begin();
      delay(1000); // DHT11 needs a moment after re-init
      Serial.println("# Sensor type changed to DHT11");
    }
    else if (strcmp(typeStr, "DHT22") == 0 && currentDhtType != DHT22)
    {
      currentDhtType = DHT22;
      dht = DHT(DHTPIN, DHT22);
      dht.begin();
      delay(1000);
      Serial.println("# Sensor type changed to DHT22");
    }
    else
    {
      Serial.print("# Sensor type already set to ");
      Serial.println(typeStr);
    }
  }
  else if (strlen(cmd) > 0)
  {
    Serial.print("# Unknown command: ");
    Serial.println(cmd);
  }
}
