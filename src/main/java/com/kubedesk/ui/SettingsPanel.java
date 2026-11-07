package com.kubedesk.ui;

import atlantafx.base.controls.ToggleSwitch;
import com.kubedesk.prefs.Settings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.List;

/**
 * The application Settings dialog (opened from the top-right ☰ menu). Today it houses the live
 * Overview-metrics preferences; it's the home for future app-wide settings (e.g. a dark-mode toggle).
 *
 * <p>Changes apply immediately: each control writes its {@link Settings} field, runs {@code persist}
 * (so the choice survives a restart) and runs {@code onApply} (so the running Overview reacts at once).
 */
public final class SettingsPanel {

    private SettingsPanel() {
    }

    /**
     * @param persist run after changing {@code settings} so the choice survives a restart
     * @param onApply run so the live Overview reflects the change immediately
     */
    public static void show(Window owner, Settings settings, Runnable persist, Runnable onApply) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Settings");

        Label metricsHeader = new Label("Live cluster metrics");
        metricsHeader.getStyleClass().add("nav-section");

        // On/off for the whole Overview metrics section.
        ToggleSwitch enabled = new ToggleSwitch();
        enabled.setSelected(settings.metricsChartsEnabled);
        Label enabledLabel = new Label("Show live CPU / memory charts on the Overview");
        Region sp1 = new Region();
        HBox.setHgrow(sp1, Priority.ALWAYS);
        HBox enabledRow = new HBox(10, enabledLabel, sp1, enabled);
        enabledRow.setAlignment(Pos.CENTER_LEFT);

        // Poll interval.
        ChoiceBox<Integer> interval = new ChoiceBox<>();
        interval.getItems().setAll(5, 10, 30);
        interval.setConverter(secondsConverter());
        interval.setValue(nearest(settings.metricsIntervalSeconds, List.of(5, 10, 30)));
        HBox intervalRow = labeled("Sample every", interval);

        // Rolling window.
        ChoiceBox<Integer> window = new ChoiceBox<>();
        window.getItems().setAll(5, 15, 60);
        window.setConverter(minutesConverter());
        window.setValue(nearest(settings.metricsWindowMinutes, List.of(5, 15, 60)));
        HBox windowRow = labeled("Show the last", window);

        // Persist across restarts.
        ToggleSwitch persistSwitch = new ToggleSwitch();
        persistSwitch.setSelected(settings.metricsPersist);
        Label persistLabel = new Label("Keep history across restarts");
        Region sp2 = new Region();
        HBox.setHgrow(sp2, Priority.ALWAYS);
        HBox persistRow = new HBox(10, persistLabel, sp2, persistSwitch);
        persistRow.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(
                "Kubernetes' metrics-server keeps no history, so these charts build up while the app is "
                + "open (needs metrics-server installed). Enabling \"keep history\" saves the collected "
                + "samples to ~/.kubedesk so the chart refills after a restart — it can't backfill time "
                + "the app wasn't running. For true long-range history, point KubeDesk at Prometheus "
                + "(planned).");
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        hint.getStyleClass().add("muted");

        Runnable apply = () -> {
            settings.metricsChartsEnabled = enabled.isSelected();
            settings.metricsIntervalSeconds = interval.getValue() != null ? interval.getValue() : 10;
            settings.metricsWindowMinutes = window.getValue() != null ? window.getValue() : 15;
            settings.metricsPersist = persistSwitch.isSelected();
            // Dependent controls follow the master switch.
            interval.setDisable(!settings.metricsChartsEnabled);
            window.setDisable(!settings.metricsChartsEnabled);
            persistSwitch.setDisable(!settings.metricsChartsEnabled);
            persist.run();
            onApply.run();
        };
        enabled.selectedProperty().addListener((o, a, b) -> apply.run());
        interval.valueProperty().addListener((o, a, b) -> apply.run());
        window.valueProperty().addListener((o, a, b) -> apply.run());
        persistSwitch.selectedProperty().addListener((o, a, b) -> apply.run());
        // Initial enable/disable state without re-persisting.
        interval.setDisable(!settings.metricsChartsEnabled);
        window.setDisable(!settings.metricsChartsEnabled);
        persistSwitch.setDisable(!settings.metricsChartsEnabled);

        VBox root = new VBox(12, metricsHeader, enabledRow, intervalRow, windowRow, persistRow,
                new Separator(), hint);
        root.setPadding(new Insets(16));
        root.setPrefWidth(480);
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Done", ButtonBar.ButtonData.OK_DONE));
        dialog.showAndWait();
    }

    private static HBox labeled(String text, Region control) {
        Label l = new Label(text);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, l, spacer, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Integer nearest(int value, List<Integer> options) {
        Integer best = options.get(0);
        int bestDiff = Integer.MAX_VALUE;
        for (Integer o : options) {
            int d = Math.abs(o - value);
            if (d < bestDiff) {
                bestDiff = d;
                best = o;
            }
        }
        return best;
    }

    private static StringConverter<Integer> secondsConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(Integer s) {
                return s == null ? "" : s + "s";
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        };
    }

    private static StringConverter<Integer> minutesConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(Integer m) {
                if (m == null) {
                    return "";
                }
                return m >= 60 ? (m / 60) + "h" : m + "m";
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        };
    }
}
