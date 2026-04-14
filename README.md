# ⚡ IoT Smart Energy Management Dashboard

A real-time desktop application that monitors IoT sensor data — either from a **physical Arduino sensor** (temperature + humidity via USB serial) or from a built-in **Gaussian simulation engine**. Voltage and Active Power are always simulated.

Built with **Java 17 · JavaFX 17 · MySQL 8 · HikariCP · iText 7 · jSerialComm**.

---

## 📸 What It Does

**The application first starts in Full Simulation Mode.**
| Mode | Sensors |
|---|---|
| **Real Sensor Mode** (`USE_REAL_SENSOR = true`) | Temperature + Humidity from Arduino DHT22/DHT11 via USB; Voltage + Power simulated | -> REFER BELOW INFORMATION FOR CONNECTION OF REAL SENSORS
| **Full Simulation Mode** (`USE_REAL_SENSOR = false`) | All four sensors Gaussian-simulated |

Live LineCharts scroll at 1 Hz with a 60-point sliding window. All readings persist to MySQL and are exportable as a styled PDF report.

---

## 📋 Prerequisites

| Tool | Minimum Version | Download |
|---|---|---|
| Java JDK | 17 (LTS) | [Adoptium](https://adoptium.net) |
| Apache Maven | 3.8+ | [maven.apache.org](https://maven.apache.org/download.cgi) |
| MySQL Server | 8.0+ | [mysql.com](https://dev.mysql.com/downloads/mysql/) |
| Arduino IDE *(optional, for real sensor)* | 2.x | [arduino.cc](https://www.arduino.cc/en/software) |

---

## 🚀 Local Setup (First Time)

### 1 — Clone the repo

```bash
git clone https://github.com/YOUR_USERNAME/iot-dashboard.git
cd iot-dashboard
```

### 2 — Configure your database credentials

```bash
cp database.properties.example src/main/resources/database.properties
```
Open `src/main/resources/database.properties` and fill in your MySQL credentials:

```properties
db.url=jdbc:mysql://localhost:3306/iot_dashboard?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true
db.username=root
db.password=YOUR_ACTUAL_PASSWORD_HERE
```

### 3 — Create the MySQL database

```sql
-- In MySQL Workbench or any MySQL client:
CREATE DATABASE IF NOT EXISTS iot_dashboard;
```

The application auto-creates all tables and seeds the default admin user on first launch.
Or run the full setup script manually: `mysql -u root -p < setup.sql`

### 4 — Build and run

```bash
# Recommended — no separate JavaFX install needed
mvn clean compile
mvn javafx:run

# Alternative — fat JAR (requires JavaFX SDK)
mvn clean package
java --module-path /path/to/javafx-sdk/lib \
     --add-modules javafx.controls,javafx.fxml \
     -jar target/iot-dashboard-1.0.0.jar
```

### 5 — Log in

| Field | Default value |
|---|---|
| Username | `admin` |
| Password | `admin123` |

> Change this password after first login for any non-demo use.

---

## 🌐 Deploying to a Remote Server (Cloud / VPS)

This is a **JavaFX desktop application** — it renders a GUI window and requires a display. It cannot run as a headless web server natively. The recommended approach for remote access is running it on a VM with a virtual desktop accessible via VNC.

### Provision a VM (any cloud provider)

Minimum specs: **2 vCPU · 2 GB RAM · 20 GB disk**  
Recommended OS: **Ubuntu 22.04 LTS**  
Firewall: open port `22` (SSH) only; keep `3306` private.

### Install dependencies on the VM

```bash
sudo apt update && sudo apt upgrade -y

# Java 17
sudo apt install -y openjdk-17-jdk

# Maven
sudo apt install -y maven

# MySQL
sudo apt install -y mysql-server
sudo mysql_secure_installation

# Lightweight desktop + VNC for GUI access
sudo apt install -y xfce4 xfce4-goodies tightvncserver

# GUI rendering libraries for JavaFX
sudo apt install -y libgtk-3-0 libgl1
```

### Start a VNC desktop session

```bash
vncserver :1 -geometry 1280x800 -depth 24
```

Connect from your local machine with a VNC client ([TigerVNC](https://tigervnc.org/), [RealVNC Viewer](https://www.realvnc.com/en/connect/download/viewer/)):

```
Host: YOUR_VM_IP:5901
```

### Clone and run on the VM

```bash
git clone https://github.com/YOUR_USERNAME/iot-dashboard.git
cd iot-dashboard
cp database.properties.example src/main/resources/database.properties

# Edit credentials, then:
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS iot_dashboard;"
mvn javafx:run
```

The dashboard window appears inside your VNC session. When a user connects a sensor to the server's USB port, live readings appear immediately.

> **For laptop/desktop users:** there is no need for VNC — just run the app locally and plug in the Arduino.

---

## 🔌 Connecting a Real Arduino Sensor

### Hardware required

- Arduino Uno / Nano / Mega
- DHT22 (recommended) or DHT11 sensor
- 10 kΩ pull-up resistor
- USB cable

### Wiring

```
DHT22 Pin 1 (VCC)  → Arduino 3.3V
DHT22 Pin 2 (DATA) → Arduino Digital Pin 2  +  10kΩ to 3.3V
DHT22 Pin 3 (NC)   → not connected
DHT22 Pin 4 (GND)  → Arduino GND

OR

DHT11 Pin 1 (VCC) → Arduino 5V
DHT11 Pin 2 (DATA) → Arduino Digital Pin 2
DHT11 Pin 3 (GND) → Arduino GND

(Info provided for Arduino Uno Controller...confirm and check the wirings using external sources before powering on the hardware and sensors)

```

### Flash the sketch

Open `arduino_dht_sensor.ino` in Arduino IDE.
Upload the sketch. Open Serial Monitor at **9600 baud** — you should see:

```
T:25.30,H:60.10
T:25.28,H:60.15
```

### Find your COM port

**Windows:** Device Manager → Ports (COM & LPT) → `USB Serial Device (COMx)`

**Linux / macOS:**
```bash
ls /dev/tty*
# Typical: /dev/ttyUSB0  /dev/ttyACM0  /dev/cu.usbmodem14101
```

Or print available ports from code — add temporarily to `DashboardApplication.java`:
```java
System.out.println(Arrays.toString(RealSensorReader.listAvailablePorts()));
```

### Configure the dashboard

Edit `src/main/java/com/iot/dashboard/controller/DashboardController.java` (around line 78):

```java
private static final boolean USE_REAL_SENSOR = true;          // ← enable real sensor
private static final String  SERIAL_PORT     = "COM3";        // ← Windows example
// private static final String SERIAL_PORT   = "/dev/ttyUSB0"; // Linux
// private static final String SERIAL_PORT   = "/dev/cu.usbmodem14101"; // macOS
private static final int     BAUD_RATE       = 9600;          // ← must match Arduino sketch
```

Then run: `mvn javafx:run`

Temperature and Humidity charts update live from your sensor. Voltage and Active Power remain simulated. All readings are stored in MySQL.

---

## 🏗️ Project Structure

```
iot-dashboard/
├── .gitignore                           ← excludes database.properties + build output
├── database.properties.example          ← safe template — committed to git
├── pom.xml                              ← Maven dependencies + build config
├── setup.sql                            ← optional manual DB setup
├── arduino_dht_sensor.ino               ← Arduino firmware for DHT22
├── README.md
└── src/
    ├── main/
    │   ├── java/com/iot/dashboard/
    │   │   ├── DashboardApplication.java
    │   │   ├── model/          SensorData · SensorType · AdminUser
    │   │   ├── database/       DatabaseManager (HikariCP singleton) · DatabaseSetup
    │   │   ├── simulation/     SensorSimulator (Gaussian) · RealSensorReader (USB serial)
    │   │   ├── controller/     LoginController · DashboardController
    │   │   ├── report/         ReportGenerator (iText 7 PDF)
    │   │   └── util/           PasswordUtil (BCrypt) · AlertUtil
    │   └── resources/
    │       ├── database.properties        ← YOUR LOCAL CREDENTIALS (git-ignored)
    │       └── com/iot/dashboard/         login.fxml · dashboard.fxml · styles.css
    └── test/
        └── java/com/iot/dashboard/
            ├── DatabaseManagerTest.java   ← H2 in-memory
            └── SensorSimulatorTest.java
```

---

## 🧪 Running Tests

Tests use H2 in-memory database — **no MySQL required**.

```bash
mvn test
mvn test -Dtest=DatabaseManagerTest
mvn test -Dtest=SensorSimulatorTest
```

---

## 📊 PDF Report Generation

1. Let the dashboard run for ≥30 seconds.
2. Click **📄 Generate Report**.
3. Pick a save location.
4. The PDF contains summary statistics (Min/Max/Avg per sensor) and paginated raw readings.

---

## 🔒 Security Notes

| Concern | Mitigation |
|---|---|
| Password storage | BCrypt work-factor 10 (~100 ms per verification) |
| SQL injection | All queries use `PreparedStatement` |
| Credential exposure | `database.properties` is git-ignored |
| User enumeration | Login always shows the same generic error message |

---

## 🔧 Troubleshooting

| Error | Fix |
|---|---|
| `Communications link failure` | MySQL not running — start the service |
| `Access denied for user 'root'@'localhost'` | Wrong password in `database.properties` |
| `No suitable driver found` | Run `mvn clean compile` first |
| Charts blank after login | Normal — wait 1–2 s for first readings |
| `JavaFX runtime not found` | Use `mvn javafx:run` |
| `Could not open port 'COM3'` | Wrong port name — check Device Manager or `ls /dev/tty*` |
| `Skipping malformed line` | Check Serial Monitor — output must be `T:xx.xx,H:xx.xx` |
| Port opens but no data | Baud rate mismatch — verify `Serial.begin()` in the `.ino` matches `BAUD_RATE` |

---

## 🎛️ Sensor Simulation Parameters

| Sensor | Mean | Std Dev | Range | Unit |
|---|---|---|---|---|
| Temperature | 25 | ±2 | 10–50 | °C |
| Humidity | 60 | ±5 | 20–100 | % |
| Voltage | 230 | ±5 | 200–260 | V |
| Active Power | 1500 | ±150 | 200–4000 | W |

---

## 🔮 Future Enhancements

- REST API layer (Javalin / Spring Boot) for browser-based access to live readings
- Multi-device support
- Real-time threshold alerts (e.g., notify when voltage < 210 V)
- Historical trend charts (hourly / daily / weekly)
- CSV export alongside PDF
- Docker Compose setup (app + MySQL in one command)
- Role-based access control

---

## 📦 Dependencies

| Library | Version | Purpose |
|---|---|---|
| JavaFX | 17.0.6 | GUI framework |
| MySQL Connector/J | 8.1.0 | JDBC driver |
| HikariCP | 5.1.0 | Connection pooling |
| iText 7 | 7.2.5 | PDF generation |
| jBCrypt | 0.4 | Password hashing |
| jSerialComm | 2.10.4 | USB serial (Arduino) |
| JUnit 5 | 5.10.1 | Unit testing |
| Mockito | 5.6.0 | Test mocking |
| H2 | 2.2.224 | In-memory DB for tests |
| SLF4J Simple | 2.0.9 | Logging |

---

## 📄 License

MIT — free to use, modify, and distribute. Attribution appreciated.
