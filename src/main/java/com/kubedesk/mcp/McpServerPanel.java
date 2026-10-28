package com.kubedesk.mcp;

import com.kubedesk.prefs.Settings;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
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

        CheckBox enable = new CheckBox("Enable MCP server");

        Spinner<Integer> port = new Spinner<>(1024, 65535, settings.mcpPort);
        port.setEditable(true);
        port.setPrefWidth(120);

        Label status = new Label();
        TextField endpoint = new TextField();
        endpoint.setEditable(false);
        endpoint.setPrefWidth(280);
        Button copy = new Button("Copy");

        Runnable refresh = () -> {
            boolean running = server.isRunning();
            enable.setSelected(running);
            port.setDisable(running); // change the port while stopped, then re-enable
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

        enable.setOnAction(e -> {
            if (enable.isSelected()) {
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
        grid.addRow(1, new Label("Status:"), status);
        grid.addRow(2, new Label("Endpoint:"), new HBox(6, endpoint, copy));

        VBox root = new VBox(14, banner, new Separator(), enable, grid);
        root.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        refresh.run();
        dialog.showAndWait();
    }
}
