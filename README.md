<p align="center">
   <img
src="https://img.shields.io/badge/IoT%20Dashboard-Smart%20Energy%20Management-0EA5E9?style=for-the-badge&logo=github&logoColor=white&labelColor=111827" alt="IoT Dashboard" />
</p>

<p align="center">
   <strong>
      Real-time IoT sensor monitoring with live dashboards, historical reports, and flexible data sources.
   </strong><br/>
   Connect physical Arduino sensors or use the built-in simulation engine for testing.
</p>

<p align="center">
   <a href="https://github.com/c-varshith/iot_management_dashboard"><img
src="https://img.shields.io/badge/GitHub-Source%20repo-111827?style=for-the-badge&logo=github&logoColor=white" alt="Source repo" /></a>
   <a href="#sensor-modes"><img
src="https://img.shields.io/badge/Sensor%20Modes-Real%20%2B%20Simulation-FF6B35?style=for-the-badge&labelColor=111827" alt="Sensor modes" /></a>
</p>

<p align="center">
   <a href="/LICENSE"><img src="https://img.shields.io/badge/License-MIT-111827?style=for-the-badge" alt="license"/></a>
   <a href="https://github.com/c-varshith/iot_management_dashboard/releases/latest"><img src="https://img.shields.io/github/v/release/c-varshith/iot_management_dashboard?style=for-the-badge" alt="release"/></a>
   <a href="https://github.com/c-varshith/iot_management_dashboard/actions"><img src="https://img.shields.io/github/actions/workflow/status/c-varshith/iot_management_dashboard/ci.yml?style=for-the-badge&label=build" alt="build status"/></a>
   <a href="https://github.com/c-varshith/iot_management_dashboard"><img src="https://img.shields.io/github/languages/top/c-varshith/iot_management_dashboard?style=for-the-badge" alt="languages"/></a>
</p>

<p align="center">
   <a href="#features">Features</a> &bull;
   <a href="#sensor-modes">Sensor Modes</a> &bull;
   <a href="#running-locally">Setup</a> &bull;
   <a href="#tech-stack">Stack</a>
</p>

> **Default:** Full simulation mode. Switch to real sensor mode from the dashboard toggle or the external config file.

> **Release builds:** Linux publishes a zip package and Windows publishes an installer `.exe`. Both include the application and a bundled Java runtime. You still need a working MySQL database and a valid `database.properties` configuration.

| At a glance | Value |
|---|---|
| Sensor sources | Arduino (real) or Gaussian simulation |
| Real-time UI | JavaFX LineCharts with 1 Hz refresh |
| Data persistence | MySQL with HikariCP connection pooling |
| Reporting | Styled PDF export via iText 7 |
| Backend | Java 17, Maven, JDBC |

---

## Features

- 📊 **Real-Time Dashboards** — Live temperature, humidity, voltage, and power LineCharts with 60-point sliding window
- 🔌 **Dual Data Sources** — Switch between physical Arduino DHT22/DHT11 sensors or simulation mode
- 💾 **Persistent Storage** — All sensor readings automatically logged to MySQL with optimized batch writes
- 📄 **PDF Reports** — Export styled sensor data and analytics as professional PDF documents
- 🔐 **Admin Console** — Secure dashboard with admin authentication and user management
- 📈 **Historical Analytics** — Query and visualize past sensor data with date range filtering
- 🎛️ **Flexible Configuration** — Toggle between real sensors and simulation in-app or via config

---

## Sensor Modes

The application supports two operational modes. Set `use.real.sensor=true|false` in your config, or use the dashboard toggle after login.

| Mode | Data Source | Best For |
|---|---|---|
| **Real Sensor Mode** | Arduino DHT22/DHT11 (temp + humidity via USB) + simulated voltage & power | Production IoT monitoring, physical environments |
| **Full Simulation Mode** | All four sensors (temperature, humidity, voltage, power) simulated with Gaussian distribution | Development, testing, demos |

Live LineCharts refresh at **1 Hz** with a **60-point sliding window**. All readings persist to MySQL and are queryable by date range.

---

## ⚙️ Configuration & Settings

The application is now fully configurable **without requiring rebuilds**. Configuration can be managed in two ways:

### Option 1: Settings UI (Recommended for Release Builds)

After logging in to the dashboard, use the **Real Sensor / Simulation** toggle for quick switching, or click **⚙ Settings** for the full configuration dialog.

#### Database Configuration
- **Database URL** — JDBC connection string (change host, port, or database name)
- **Username** — MySQL database user
- **Password** — MySQL database password

#### Sensor Configuration
- **Use Real Sensor** — Toggle between:
  - ✓ **Enabled**: Temperature and Humidity from Arduino; Voltage and Power simulated
  - ✗ **Disabled**: All four sensors fully simulated (default)
- **Serial Port** — COM port or tty device (e.g., `COM3` on Windows, `/dev/ttyUSB0` on Linux)
- **Baud Rate** — Serial communication speed (typically `9600` or `115200`)

**After saving settings, the application will:**
1. Write the new configuration to `~/.iot-dashboard/config.properties`
2. Rebuild the database connection pool if the database credentials changed
3. Restart all sensor connections with the new settings
4. Display a confirmation message

### Option 2: Config File (Direct Edit)

Edit the external configuration file directly:

**Linux/macOS:**
```bash
nano ~/.iot-dashboard/config.properties
```

**Windows:**
```
%USERPROFILE%\.iot-dashboard\config.properties
```

Example configuration:
```properties
# Database
db.url=jdbc:mysql://localhost:3306/iot_dashboard?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true
db.username=root
db.password=your_mysql_password

# Sensor Mode
use.real.sensor=false
serial.port=/dev/ttyUSB0
baud.rate=9600
```

You can also override the config file path and individual values at launch:

```bash
java \
  -Diot.dashboard.config=/path/to/config.properties \
  -Diot.dashboard.db.password=your_mysql_password \
  -Diot.dashboard.use.real.sensor=true \
  -jar iot-dashboard.jar
```

Environment variables are supported too:

- `IOT_DASHBOARD_CONFIG`
- `DB_PASSWORD`
- `USE_REAL_SENSOR`
- `SERIAL_PORT`
- `BAUD_RATE`

**Note:** Changes take effect after restarting the application or clicking Settings > Save & Apply, and the dashboard toggle persists the sensor mode immediately.

### Configuration Priority

The application loads configuration in this order:

1. **JVM / environment overrides** — highest priority
2. **External file** (`~/.iot-dashboard/config.properties` by default, or `IOT_DASHBOARD_CONFIG`) — if present, overrides defaults
3. **Embedded defaults** — fallback values for fresh installations

---

## 📋 Prerequisites

| Tool | Minimum Version | Download |
|---|---|---|
| Java JDK | 17 (LTS) | [Adoptium](https://adoptium.net) |
| Apache Maven | 3.8+ | [maven.apache.org](https://maven.apache.org/download.cgi) |
| MySQL Server | 8.0+ | [mysql.com](https://dev.mysql.com/downloads/mysql/) |
| Arduino IDE *(optional, for real sensor)* | 2.x | [arduino.cc](https://www.arduino.cc/en/software) |

---

## Run the Linux Release

### 1. Download the release asset

From the GitHub release page, download `iot-dashboard-linux.zip`.

### 2. Extract the archive

```bash
unzip iot-dashboard-linux.zip
cd iot-dashboard
```

### 3. Prepare MySQL

Make sure MySQL 8 is running and create the database:

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS iot_dashboard;"
```

The app runs its own schema setup on startup, but you can also initialize the schema manually with `setup.sql` if you prefer.

### 4. Configure database credentials (if needed)

**For packaged releases:** The first time you run the app, check if the default MySQL credentials work:
- Default: `localhost:3306`, username: `root`, password: (empty or default)

If your MySQL uses **different credentials**, you have two options:

**Option A: Use the Settings UI**
1. Launch the app: `./bin/iot-dashboard`
2. Log in with `admin` / `admin123`
3. Click the **⚙ Settings** button
4. Update Database URL, Username, and Password
5. Click **Save & Apply**

The new credentials will be saved to `~/.iot-dashboard/config.properties` and used for all future launches.

**Option B: Rebuild from source** (if you prefer not to use the Settings UI)
- Clone the repo, edit `src/main/resources/database.properties`, rebuild and repackage

### 5. Run the app

```bash
./bin/iot-dashboard
```

If the launcher is not executable on your system, run:

```bash
chmod +x ./bin/iot-dashboard
./bin/iot-dashboard
```

### 6. Log in

| Field | Default Value |
|---|---|
| Username | `admin` |
| Password | `admin123` |

> ⚠️ Change the default password after first login.

## Run from Source

Use these steps if you want to modify the code, change the database settings, or rebuild the app yourself.

### 1. Clone the repo

```bash
git clone https://github.com/c-varshith/iot_management_dashboard.git
cd iot_management_dashboard
```

### 2. Configure database credentials

Copy the template configuration file:

```bash
cp database.properties.example src/main/resources/database.properties
```

Edit `src/main/resources/database.properties` with your MySQL server credentials, or use the Settings dialog after the app starts:

```properties
db.url=jdbc:mysql://localhost:3306/iot_dashboard?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true
db.username=root
db.password=YOUR_ACTUAL_PASSWORD_HERE
```

**Important:** Prefer the external config file or the Settings dialog for day-to-day use so the password never has to live in source control.

### 3. Initialize the MySQL database

```bash
# Create the database
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS iot_dashboard;"

# Run the setup script (auto-creates tables + seeds default admin user)
mysql -u root -p iot_dashboard < setup.sql
```

Or manually run `setup.sql` via MySQL Workbench.

The script creates:
- `admin_users` table with default user `admin/admin123`
- `sensor_data` table for storing readings
- Indexes and constraints for data integrity

Note: on startup the application also runs its own database setup routine, so the database only needs to exist and be reachable.

### 4. Build and run

```bash
# Compile and launch with JavaFX Maven plugin (recommended)
mvn clean compile javafx:run

# Alternative: Build fat JAR (requires JavaFX SDK installed separately)
mvn clean package
java --module-path /path/to/javafx-sdk/lib \
     --add-modules javafx.controls,javafx.fxml \
     -jar target/iot-dashboard-*.jar
```

### 5. Log in to the dashboard

| Field | Default Value |
|---|---|
| Username | `admin` |
| Password | `admin123` |

> ⚠️ **Important:** Change this password after first login for any non-demo environment.

### 6. (Optional) Connect an Arduino sensor

See the **[🔌 Arduino Sensor Connection](#-arduino-sensor-connection)** section below for detailed hardware setup, firmware flashing, and configuration steps.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Frontend | JavaFX 17 (FXML + CSS) |
| Backend | Java 17, JDBC |
| Database | MySQL 8.0+ |
| Connection Pooling | HikariCP |
| Reporting | iText 7 (PDF) |
| Serial Communication | jSerialComm |
| Build Tool | Maven 3.8+ |
| Sensor Data | Arduino DHT22/DHT11 or Gaussian simulation |

---

## Project Structure

```
iot_management_dashboard/
├── src/main/
│   ├── java/com/iot/dashboard/
│   │   ├── DashboardApplication.java     # JavaFX entry point + main scene
│   │   ├── controller/
│   │   │   ├── DashboardController.java  # Real-time charts & sensor data
│   │   │   └── LoginController.java      # Authentication
│   │   ├── database/
│   │   │   ├── DatabaseManager.java      # JDBC operations (CRUD)
│   │   │   └── DatabaseSetup.java        # Schema initialization
│   │   ├── model/
│   │   │   ├── SensorData.java           # Sensor reading DTO
│   │   │   ├── SensorType.java           # Enum (TEMP, HUMIDITY, VOLTAGE, POWER)
│   │   │   └── AdminUser.java            # User auth model
│   │   ├── simulation/
│   │   │   ├── SensorSimulator.java      # Gaussian random data generator
│   │   │   └── RealSensorReader.java     # Arduino serial reader
│   │   ├── report/
│   │   │   └── ReportGenerator.java      # PDF export via iText
│   │   └── util/
│   │       ├── AlertUtil.java            # UI dialogs
│   │       └── PasswordUtil.java         # Password hashing & validation
│   │
│   └── resources/com/iot/dashboard/
│       ├── dashboard.fxml                # Main dashboard UI layout
│       ├── login.fxml                    # Login form layout
│       └── styles.css                    # JavaFX styling
│
├── src/test/java/com/iot/dashboard/
│   ├── DatabaseManagerTest.java          # CRUD operation tests
│   └── SensorSimulatorTest.java          # Simulation engine tests
│
├── arduino_dht_sensor.ino                # Arduino firmware (DHT22/DHT11)
├── database.properties.example           # Database config template
├── setup.sql                             # Database schema + seed data
├── pom.xml                               # Maven configuration
└── README.md
```

---

## 🌐 Deployment

### Running on a Remote Server (VM / Cloud)

This is a **JavaFX desktop application** — it requires a graphical display and cannot run as a headless web server natively. For remote access, deploy to a VM with a virtual desktop (VNC).

#### Provision a VM

Minimum specs: **2 vCPU · 2 GB RAM · 20 GB disk**  
Recommended OS: **Ubuntu 22.04 LTS**  
Firewall: open port `22` (SSH) only; keep MySQL port `3306` private.

#### Install dependencies on the VM

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

#### Set up VNC desktop session

```bash
vncserver :1 -geometry 1280x800 -depth 24
```

Connect from your local machine with a VNC client ([TigerVNC](https://tigervnc.org/), [RealVNC Viewer](https://www.realvnc.com/en/connect/download/viewer/)):

```
Host: YOUR_VM_IP:5901
```

#### Clone and run on the VM

```bash
git clone https://github.com/c-varshith/iot_management_dashboard.git
cd iot_management_dashboard
cp database.properties.example src/main/resources/database.properties

# Edit credentials, then:
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS iot_dashboard;"
mvn javafx:run
```

The dashboard window appears inside your VNC session. When a sensor connects to the server's USB port, live readings appear immediately.

> **For laptop/desktop users:** no VNC needed — just run the app locally and plug in the Arduino via USB.

---

## 🔌 Arduino Sensor Connection

### Hardware Required

- Arduino Uno / Nano / Mega
- DHT22 (recommended) or DHT11 sensor
- 10 kΩ pull-up resistor (for DHT22)
- USB cable

### Wiring Diagram

```
DHT22:
  Pin 1 (VCC)  → Arduino 3.3V
  Pin 2 (DATA) → Arduino Pin 2 + 10kΩ resistor to 3.3V
  Pin 3 (NC)   → not connected
  Pin 4 (GND)  → Arduino GND

DHT11:
  Pin 1 (VCC) → Arduino 5V
  Pin 2 (DATA) → Arduino Pin 2
  Pin 3 (GND) → Arduino GND
```

> ℹ️ Confirm wiring against your sensor's datasheet before powering on.

### 1. Flash the Arduino firmware

1. Open `arduino_dht_sensor.ino` in [Arduino IDE](https://www.arduino.cc/en/software)
2. Select your board and COM port
3. Click **Upload**
4. Open **Serial Monitor** at 9600 baud — confirm output like:
   ```
   T:25.30,H:60.10
   T:25.28,H:60.15
   ```

### 2. Identify your COM port

**Windows:**
- Device Manager → Ports (COM & LPT) → `USB Serial Device (COMx)`

**Linux / macOS:**
```bash
ls /dev/tty*
# Typical: /dev/ttyUSB0, /dev/ttyACM0, or /dev/cu.usbmodem*
```

**Java method** (add temporarily to `DashboardApplication.java`):
```java
System.out.println(Arrays.toString(RealSensorReader.listAvailablePorts()));
```

### 3. Enable real sensor mode

Use either:

- the dashboard toggle after login
- `use.real.sensor=true` in `~/.iot-dashboard/config.properties`
- `-Diot.dashboard.use.real.sensor=true` at launch

### 4. Restart the application

```bash
mvn javafx:run
```

**Temperature + Humidity** charts now update live from your sensor. **Voltage + Power** remain simulated. All readings persist to MySQL.

---

## 🧪 Testing

Tests use an H2 in-memory database — **no MySQL required**.

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=DatabaseManagerTest
mvn test -Dtest=SensorSimulatorTest
```

---

## 📊 PDF Report Generation

1. Let the dashboard run for at least **30 seconds** to collect readings.
2. Click the **📄 Generate Report** button.
3. Choose a save location.
4. The PDF contains:
   - Summary statistics (Min/Max/Avg) per sensor
   - Paginated raw readings table
   - Timestamp and report metadata

---

## ⚙️ Configuration

### Database Properties

Edit [src/main/resources/database.properties](src/main/resources/database.properties):

```properties
db.url=jdbc:mysql://localhost:3306/iot_dashboard?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true
db.username=root
db.password=YOUR_PASSWORD
```

This file is **git-ignored** — never commit credentials.

### Sensor Simulation Parameters

Gaussian-distributed values (used in full simulation mode or for voltage/power):

| Sensor | Mean | Std Dev | Min–Max Range | Unit |
|---|---|---|---|---|
| Temperature | 25 | ±2 | 10–50 | °C |
| Humidity | 60 | ±5 | 20–100 | % |
| Voltage | 230 | ±5 | 200–260 | V |
| Active Power | 1500 | ±150 | 200–4000 | W |

---

## 🔒 Security

| Aspect | Implementation |
|---|---|
| Password hashing | BCrypt (work factor 10, ~100 ms per check) |
| SQL injection prevention | All queries use `PreparedStatement` |
| Credential exposure | `database.properties` is `.gitignore`'d |
| Login enumeration | Generic error message for all login failures |
| MySQL access | Local connection only; configure firewall for remote VMs |

---

## 🆘 Troubleshooting

| Issue | Solution |
|---|---|
| `Communications link failure` | Ensure MySQL is running: `sudo service mysql start` |
| `Access denied for user 'root'@'localhost'` | Check password in `database.properties` |
| `No suitable driver found` | Run `mvn clean compile` to download JDBC driver |
| Charts blank after login | Normal — wait 1–2 seconds for initial data |
| `JavaFX runtime not found` | Use `mvn javafx:run` instead of plain `java` |
| `Could not open port 'COM3'` | Verify port in Device Manager or with `ls /dev/tty*` |
| `Skipping malformed line` | Check Serial Monitor — output must be `T:xx.xx,H:xx.xx` |
| No data from sensor | Confirm baud rate `Serial.begin()` in `.ino` matches `BAUD_RATE` constant |
| MySQL `Access denied` on VM | Verify VM firewall allows localhost connections |

---

## 🔮 Future Enhancements

- REST API layer (Javalin / Spring Boot) for browser-based live access
- Multi-device support (multiple Arduino boards)
- Real-time threshold alerts (e.g., notify when voltage < 210 V)
- Historical trend charts (hourly, daily, weekly aggregations)
- CSV export alongside PDF
- Docker Compose setup (app + MySQL in one command)
- Role-based access control (admin, viewer, editor roles)

---

## 📦 Dependencies

| Library | Version | Purpose |
|---|---|---|
| JavaFX | 17.0.6 | GUI framework (FXML + CSS) |
| MySQL Connector/J | 8.1.0 | JDBC driver for MySQL |
| HikariCP | 5.1.0 | Connection pooling & lifecycle management |
| iText 7 | 7.2.5 | PDF generation & styling |
| jBCrypt | 0.4 | Secure password hashing |
| jSerialComm | 2.10.4 | USB serial communication (Arduino) |
| JUnit 5 | 5.10.1 | Unit testing framework |
| Mockito | 5.6.0 | Test mocking |
| H2 Database | 2.2.224 | In-memory DB for tests |
| SLF4J Simple | 2.0.9 | Logging facade |

---

## 📄 License

MIT — free to use, modify, and distribute. Attribution appreciated.
