package com.iot.dashboard.report;

import com.iot.dashboard.model.SensorType;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * ReportOptionsDialog — shown before PDF generation.
 *
 * Loads reportoptions.fxml which shares the same CSS stylesheet
 * as the Settings dialog, giving a consistent dark-theme appearance.
 *
 * Returns a ReportOptions object; null if the user cancelled.
 */
public class ReportOptionsDialog {

    public static class ReportOptions {
        public final Set<SensorType>  selectedSensors;
        public final LocalDateTime    from;
        public final LocalDateTime    to;

        public ReportOptions(Set<SensorType> selectedSensors,
                             LocalDateTime from, LocalDateTime to) {
            this.selectedSensors = selectedSensors;
            this.from            = from;
            this.to              = to;
        }
    }

    public ReportOptions show(Window owner) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/iot/dashboard/reportoptions.fxml"));
            Parent root = loader.load();

            ReportOptionsController controller = loader.getController();

            Scene scene = new Scene(root, 460, 600);
            // Apply the same stylesheet as the rest of the app
            scene.getStylesheets().add(
                    getClass().getResource("/com/iot/dashboard/styles.css").toExternalForm());

            Stage dialog = new Stage();
            dialog.initModality(Modality.WINDOW_MODAL);
            dialog.initOwner(owner);
            dialog.initStyle(StageStyle.DECORATED);
            dialog.setTitle("Report Options");
            dialog.setResizable(false);
            dialog.setScene(scene);
            dialog.showAndWait();

            return controller.getResult();

        } catch (Exception e) {
            throw new RuntimeException("Failed to load report options dialog", e);
        }
    }
}
