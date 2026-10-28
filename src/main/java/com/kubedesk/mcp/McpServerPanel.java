package com.kubedesk.mcp;

import atlantafx.base.controls.ToggleSwitch;
import com.kubedesk.prefs.Settings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * The small dialog that enables/disables the embedded {@link KubeDeskMcpServer} and shows its endpoint.
 *
 * <p>The server's lifecycle is owned by the caller (so it keeps running after this dialog closes); the
 * dialog only flips it on/off, persists the chosen port/enabled flag, and reports status.
 */
public final class McpServerPanel {

    private McpServerPanel() {
    }

    /**
     * @param persist run after changing {@code settings} so the choice survives a restart
     */
    public static void show(Window owner, KubeDeskMcpServer server, Settings settings, Runnable persist) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("MCP server (experimental)");

        Label banner = new Label(
                "Experimental — for local, dev/testing use with non-production clusters. Exposes a "
                + "read-only MCP (Model Context Protocol) server on localhost so an AI agent (e.g. Claude) "
                + "can scan this cluster's health. Loopback-only; no data leaves your machine.");
        banner.setWrapText(true);
        banner.setMaxWidth(440);
        banner.getStyleClass().add("mcp-banner");

        // A power-switch affordance (not a checkbox): it reads as "the server is on/off, right now".
        ToggleSwitch power = new ToggleSwitch();
        Label powerLabel = new Label("MCP server");
        powerLabel.getStyleClass().add("mcp-power-label");
        Region powerSpacer = new Region();
        HBox.setHgrow(powerSpacer, Priority.ALWAYS);
        Label status = new Label();
        HBox powerRow = new HBox(10, powerLabel, powerSpacer, status, power);
        powerRow.setAlignment(Pos.CENTER_LEFT);

        Spinner<Integer> port = new Spinner<>(1024, 65535, settings.mcpPort);
        port.setEditable(true);
        port.setPrefWidth(120);

        TextField endpoint = new TextField();
        endpoint.setEditable(false);
        endpoint.setPrefWidth(280);
        Button copy = new Button("Copy");

        Label hint = new Label(
                "KubeDesk keeps the server running after you close this window — turn it off here, "
                + "or it stops when KubeDesk exits.");
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        hint.getStyleClass().add("mcp-hint");

        // Reflecting server state back into the switch must not re-trigger start/stop.
        boolean[] syncing = {false};
        Runnable refresh = () -> {
            boolean running = server.isRunning();
            syncing[0] = true;
            power.setSelected(running);
            syncing[0] = false;
            port.setDisable(running); // change the port while stopped, then flip it back on
            endpoint.setDisable(!running);
            copy.setDisable(!running);
            if (running) {
                status.setText("● Running");
                status.getStyleClass().setAll("mcp-status-running");
                endpoint.setText(server.endpoint());
            } else {
                status.setText("○ Stopped");
                status.getStyleClass().setAll("mcp-status-stopped");
                endpoint.clear();
            }
        };

        power.selectedProperty().addListener((obs, was, on) -> {
            if (syncing[0]) {
                return; // programmatic sync from refresh(), not a user toggle
            }
            if (on) {
                int p = port.getValue();
                try {
                    server.start(p);
                    settings.mcpPort = p;
                    settings.mcpEnabled = true;
                    persist.run();
                } catch (Exception ex) {
                    Alert a = new Alert(Alert.AlertType.ERROR,
                            "Couldn't start the MCP server on port " + p + ":\n" + ex.getMessage());
                    a.initOwner(owner);
                    a.showAndWait();
                    // refresh() below flips the switch back off, since the server didn't start.
                }
            } else {
                server.stop();
                settings.mcpEnabled = false;
                persist.run();
            }
            refresh.run();
        });

        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(server.endpoint());
            Clipboard.getSystemClipboard().setContent(cc);
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Port:"), port);
        grid.addRow(1, new Label("Endpoint:"), new HBox(6, endpoint, copy));

        VBox root = new VBox(14, banner, new Separator(), powerRow, grid, hint);
        root.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(root);
        // "Done" dismisses the dialog; it does NOT stop the server (the switch does).
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Done", ButtonBar.ButtonData.OK_DONE));

        refresh.run();
        dialog.showAndWait();
    }
}
