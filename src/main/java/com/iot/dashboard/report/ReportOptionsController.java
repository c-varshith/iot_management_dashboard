package com.iot.dashboard.report;

import com.iot.dashboard.model.SensorType;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.net.URL;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Controller for reportoptions.fxml.
 * Collects sensor selection + time range and exposes them via getResult().
 */
public class ReportOptionsController implements Initializable {

    // ── Sensor checkboxes ─────────────────────────────────────────────────────
    @FXML private CheckBox cbTemperature;
    @FXML private CheckBox cbHumidity;
    @FXML private CheckBox cbVoltage;
    @FXML private CheckBox cbActivePower;

    // ── Select / Deselect All ─────────────────────────────────────────────────
    @FXML private Button selectAllBtn;
    @FXML private Button deselectAllBtn;

    // ── Time range radios ─────────────────────────────────────────────────────
    @FXML private RadioButton r1h;
    @FXML private RadioButton r6h;
    @FXML private RadioButton r24h;
    @FXML private RadioButton rAll;

    // ── Footer ────────────────────────────────────────────────────────────────
    @FXML private Label  validationLabel;
    @FXML private Button cancelBtn;
    @FXML private Button generateBtn;

    private ReportOptionsDialog.ReportOptions result = null;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        // Wire radio buttons into a single toggle group
        ToggleGroup tg = new ToggleGroup();
        r1h.setToggleGroup(tg);
        r6h.setToggleGroup(tg);
        r24h.setToggleGroup(tg);
        rAll.setToggleGroup(tg);
        r1h.setSelected(true);

        selectAllBtn.setOnAction(e -> {
            cbTemperature.setSelected(true);
            cbHumidity.setSelected(true);
            cbVoltage.setSelected(true);
            cbActivePower.setSelected(true);
            validationLabel.setText("");
        });

        deselectAllBtn.setOnAction(e -> {
            cbTemperature.setSelected(false);
            cbHumidity.setSelected(false);
            cbVoltage.setSelected(false);
            cbActivePower.setSelected(false);
        });

        // Clear validation on any checkbox change
        for (CheckBox cb : new CheckBox[]{cbTemperature, cbHumidity, cbVoltage, cbActivePower}) {
            cb.selectedProperty().addListener((obs, o, n) -> validationLabel.setText(""));
        }

        cancelBtn.setOnAction(e -> {
            result = null;
            close();
        });

        generateBtn.setOnAction(e -> handleGenerate());
    }

    private void handleGenerate() {
        Set<SensorType> selected = new LinkedHashSet<>();
        if (cbTemperature.isSelected()) selected.add(SensorType.TEMPERATURE);
        if (cbHumidity.isSelected())    selected.add(SensorType.HUMIDITY);
        if (cbVoltage.isSelected())     selected.add(SensorType.VOLTAGE);
        if (cbActivePower.isSelected()) selected.add(SensorType.ACTIVE_POWER);

        if (selected.isEmpty()) {
            validationLabel.setText("Please select at least one sensor type.");
            return;
        }

        LocalDateTime to   = LocalDateTime.now();
        LocalDateTime from;
        if      (r1h.isSelected())  from = to.minusHours(1);
        else if (r6h.isSelected())  from = to.minusHours(6);
        else if (r24h.isSelected()) from = to.minusHours(24);
        else                        from = LocalDateTime.of(2000, 1, 1, 0, 0);

        result = new ReportOptionsDialog.ReportOptions(selected, from, to);
        close();
    }

    public ReportOptionsDialog.ReportOptions getResult() {
        return result;
    }

    private void close() {
        ((Stage) cancelBtn.getScene().getWindow()).close();
    }
}
