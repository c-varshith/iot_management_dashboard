package com.iot.dashboard.util;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Utility class for displaying standardised JavaFX dialog alerts.
 * All methods run on the JavaFX Application Thread.
 */
public final class AlertUtil {

    private AlertUtil() {}

    /**
     * Displays a modal error dialog.
     */
    public static void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStylesheets().add(
                AlertUtil.class.getResource("/com/iot/dashboard/styles.css").toExternalForm()
        );
        // Ensure the dialog is window-modal to the currently focused window so
        // it doesn't cause unexpected changes to the main stage (maximize/resize).
        Window owner = Window.getWindows().stream().filter(Window::isFocused).findFirst().orElse(null);
        if (owner != null) {
            alert.initOwner(owner);
            alert.initModality(Modality.WINDOW_MODAL);
        }
        alert.showAndWait();
    }

    /**
     * Displays a modal information dialog.
     */
    public static void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStylesheets().add(
                AlertUtil.class.getResource("/com/iot/dashboard/styles.css").toExternalForm()
        );
        Window owner = Window.getWindows().stream().filter(Window::isFocused).findFirst().orElse(null);
        if (owner != null) {
            alert.initOwner(owner);
            alert.initModality(Modality.WINDOW_MODAL);
        }
        alert.showAndWait();
    }

    /**
     * Displays a confirmation dialog and returns true if the user clicks OK.
     */
    public static boolean showConfirmation(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStylesheets().add(
                AlertUtil.class.getResource("/com/iot/dashboard/styles.css").toExternalForm()
        );
        Window owner = Window.getWindows().stream().filter(Window::isFocused).findFirst().orElse(null);
        if (owner != null) {
            alert.initOwner(owner);
            alert.initModality(Modality.WINDOW_MODAL);
        }
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }
}
