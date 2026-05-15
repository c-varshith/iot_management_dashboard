package com.iot.dashboard;

/**
 * Launcher — plain (non-JavaFX) entry point for the fat JAR.
 *
 * Why this class exists:
 *   When a JavaFX Application subclass is set as the Main-Class in MANIFEST.MF,
 *   the JVM's module system enforces strict JavaFX module rules and blocks startup
 *   when the class is loaded from the classpath (fat JAR) rather than the module path.
 *   The symptom is the exe or JAR silently doing nothing — no window, no error shown.
 *
 *   The fix is to have a plain class (no JavaFX parent) as the manifest entry point.
 *   This class simply delegates to DashboardApplication.main(), which calls
 *   Application.launch() — by the time launch() is called, JavaFX initialises
 *   correctly because it's invoked from a non-Application context.
 *
 * References:
 *   https://openjfx.io/openjfx-docs/#modular  (non-modular fat JAR section)
 */
public class Launcher {

    public static void main(String[] args) {
        DashboardApplication.main(args);
    }
}
