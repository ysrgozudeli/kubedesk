package com.kubedesk.mcp;

import atlantafx.base.controls.ToggleSwitch;
import com.kubedesk.prefs.Settings;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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
        // A small always-visible badge so the read-only nature is obvious at a glance.
        Label roBadge = new Label("READ-ONLY");
        roBadge.getStyleClass().addAll("chip", "chip-neutral", "mcp-ro-badge");
        Label status = new Label();
        HBox powerRow = new HBox(10, powerLabel, roBadge, powerSpacer, status, power);
        powerRow.setAlignment(Pos.CENTER_LEFT);

        Spinner<Integer> port = new Spinner<>(1024, 65535, settings.mcpPort);
        port.setEditable(true);
        port.setPrefWidth(120);

        TextField endpoint = new TextField();
        endpoint.setEditable(false);
        endpoint.setPrefWidth(280);
        Button copy = new Button("Copy");

        // Access level. Everything is read-only today; the write side is shown but locked until the
        // write tools (and their confirmation gate) exist, so the surface is honest about what it does.
        // A disabled switch: off (left) = Read-only, on (right) = Read & write.
        Label accessLabel = new Label("Access");
        accessLabel.getStyleClass().add("nav-section");
        ToggleSwitch accessSwitch = new ToggleSwitch();
        accessSwitch.setSelected(false); // off = read-only
        accessSwitch.setDisable(true);   // not implemented yet — intentionally locked
        Label roSide = new Label("Read-only");
        roSide.getStyleClass().add("mcp-power-label"); // the active side, emphasised
        Label rwSide = new Label("Read & write");
        rwSide.getStyleClass().add("mcp-status-stopped"); // muted: the inactive, locked side
        Label soon = new Label("coming soon");
        soon.getStyleClass().add("mcp-hint");
        HBox rwSideBox = new HBox(6, rwSide, soon);
        rwSideBox.setAlignment(Pos.CENTER_LEFT);
        HBox accessRow = new HBox(10, roSide, accessSwitch, rwSideBox);
        accessRow.setAlignment(Pos.CENTER_LEFT);
        Label accessHint = new Label(
                "Only read tools are exposed (scan, list, describe, logs). Write tools — scale, "
                + "restart, delete, apply — will arrive later behind a per-action confirmation gate.");
        accessHint.setWrapText(true);
        accessHint.setMaxWidth(440);
        accessHint.getStyleClass().add("mcp-hint");
        VBox accessBox = new VBox(6, accessLabel, accessRow, accessHint);

        // "Follow along": reflect each agent call in the main window (status line + reveal the
        // resource). Persisted so it applies even when this dialog is closed during a session.
        ToggleSwitch follow = new ToggleSwitch();
        follow.setSelected(settings.mcpFollow);
        follow.selectedProperty().addListener((obs, was, on) -> {
            settings.mcpFollow = on;
            persist.run();
        });
        Label followLabel = new Label("Follow agent in the window");
        followLabel.getStyleClass().add("mcp-power-label");
        Region followSpacer = new Region();
        HBox.setHgrow(followSpacer, Priority.ALWAYS);
        HBox followRow = new HBox(10, followLabel, followSpacer, follow);
        followRow.setAlignment(Pos.CENTER_LEFT);
        Label followHint = new Label(
                "When on, each tool call is mirrored to the status bar and the resource the agent "
                + "reads is revealed — switching namespace/kind and opening its details.");
        followHint.setWrapText(true);
        followHint.setMaxWidth(440);
        followHint.getStyleClass().add("mcp-hint");

        // Live recent-activity list — newest first — seeded from the server and updated as calls land.
        Label activityLabel = new Label("Recent activity");
        activityLabel.getStyleClass().add("nav-section");
        ListView<String> activity = new ListView<>();
        activity.setPrefHeight(150);
        activity.setPlaceholder(new Label("No agent activity yet."));
        activity.getStyleClass().add("mcp-activity");
        ObservableList<String> items = activity.getItems();
        for (McpActivity.Event ev : server.activity().snapshot()) {
            items.add(0, formatEvent(ev));
        }
        McpActivity.Listener liveLog = ev -> Platform.runLater(() -> {
            items.add(0, formatEvent(ev));
            while (items.size() > 200) {
                items.remove(items.size() - 1);
            }
        });
        server.activity().addListener(liveLog);
        dialog.setOnHidden(e -> server.activity().removeListener(liveLog));

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

        VBox root = new VBox(14, banner, new Separator(), powerRow, grid,
                new Separator(), accessBox,
                new Separator(), followRow, followHint, activityLabel, activity, hint);
        root.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(root);
        // "Done" dismisses the dialog; it does NOT stop the server (the switch does).
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Done", ButtonBar.ButtonData.OK_DONE));

        refresh.run();
        dialog.showAndWait();
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** One activity line: {@code HH:mm:ss  ✓ tool · summary → outcome}. */
    private static String formatEvent(McpActivity.Event e) {
        String t = Instant.ofEpochMilli(e.epochMillis())
                .atZone(ZoneId.systemDefault()).toLocalTime().format(TIME);
        return t + "  " + (e.ok() ? "✓" : "✗") + " " + e.tool() + " · " + e.summary()
                + (e.ok() ? " → " : " ✗ ") + e.outcome();
    }
}
