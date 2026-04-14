#include <DHT.h>

#define DHTPIN    7        // Digital pin the DHT sensor DATA line is connected to
#define DHTTYPE   DHT22    // Use DHT11 if you have the blue/smaller sensor

#define READ_INTERVAL_MS 2000

DHT dht(DHTPIN, DHTTYPE);

void setup()
{
  Serial.begin(9600);
  dht.begin();

  delay(2000);
  Serial.println("# IoT Dashboard Sensor Ready");
}

void loop()
{
  delay(READ_INTERVAL_MS);

  float humidity    = dht.readHumidity();
  float temperature = dht.readTemperature();

  if (isnan(humidity) || isnan(temperature))
  {
    Serial.println("# ERROR: Failed to read from DHT sensor — check wiring.");
    return;
  }

  Serial.print("T:");
  Serial.print(temperature, 2);
  Serial.print(",H:");
  Serial.println(humidity, 2);
}
