package com.kubedesk.ui;

import com.kubedesk.service.ClusterService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A standalone, detachable log window for one pod container. It owns its own log stream over the
 * shared {@link ClusterService}, so it keeps tailing while you navigate the main window — handy for
 * watching a rollout on a second monitor. Several can be open at once; each holds its own connection
 * and frees it when the window closes.
 *
 * <p>Deliberately self-contained (its own controls + streaming), with no reach into {@code MainView}'s
 * details-panel state — the only shared thing is the {@code ClusterService}.
 */
public final class LogWindow {

    private final ClusterService service;
    private final String ctxName;
    private final String namespace;
    private final String pod;

    private final Stage stage = new Stage();
    private final ComboBox<String> containerBox = new ComboBox<>();
    private final ComboBox<Integer> tailBox = new ComboBox<>();
    private final TextArea logsArea = new TextArea();
    private final ToggleButton follow = new ToggleButton("▶");

    private AutoCloseable logStream;
    /** Bumped on each (re)load so callbacks from a superseded stream can be ignored. */
    private int streamGen;

    public LogWindow(ClusterService service, String ctxName, String namespace, String pod,
                     String initialContainer, int initialTail, boolean startFollowing) {
        this.service = service;
        this.ctxName = ctxName;
        this.namespace = namespace;
        this.pod = pod;
        build(initialContainer, initialTail, startFollowing);
    }

    private void build(String initialContainer, int initialTail, boolean startFollowing) {
        containerBox.setMinWidth(150);
        containerBox.setOnAction(e -> loadLogs());

        tailBox.setItems(FXCollections.observableArrayList(100, 500, 1000, 5000));
        tailBox.setValue(initialTail > 0 ? initialTail : 500);
        tailBox.setOnAction(e -> loadLogs());

        Button refresh = new Button("↻");
        refresh.getStyleClass().add("icon-button");
        refresh.setOnAction(e -> loadLogs());

        // Follow play/pause — same look/semantics as the inline Logs tab (▶ start, ⏸ stop).
        follow.getStyleClass().add("log-follow");
        follow.setSelected(startFollowing);
        follow.setText(startFollowing ? "⏸" : "▶");
        follow.setTooltip(new Tooltip(startFollowing ? "Stop following" : "Follow live (kubectl logs -f)"));
        follow.setOnAction(e -> loadLogs());
        follow.selectedProperty().addListener((o, was, on) -> {
            follow.setText(on ? "⏸" : "▶");
            follow.setTooltip(new Tooltip(on ? "Stop following" : "Follow live (kubectl logs -f)"));
        });

        Button copy = new Button("⧉ Copy");
        copy.getStyleClass().add("icon-button");
        copy.setTooltip(new Tooltip("Copy the whole log tail to the clipboard"));
        copy.setOnAction(e -> {
            String text = logsArea.getText();
            if (text == null || text.isBlank()) {
                return;
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
        });

        Button save = new Button("Save…");
        save.getStyleClass().add("accent-button");
        save.setOnAction(e -> saveLogs());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox controls = new HBox(8,
                new Label("Container:"), containerBox,
                new Label("Tail:"), tailBox,
                refresh, follow, spacer, copy, save);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8, 12, 8, 12));

        logsArea.setEditable(false);
        logsArea.setWrapText(false);
        logsArea.getStyleClass().addAll("yaml-area", "logs-area");
        VBox.setVgrow(logsArea, Priority.ALWAYS);

        VBox root = new VBox(controls, logsArea);
        root.getStyleClass().add("root-pane");

        Scene scene = new Scene(root, 860, 540);
        var css = getClass().getResource("/styles/app.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        stage.setTitle(pod + " · logs — KubeDesk");
        var icon = getClass().getResourceAsStream("/icons/kubedesk.png");
        if (icon != null) {
            stage.getIcons().add(new Image(icon));
        }
        // Free the stream (and its connection) when the window is closed.
        stage.setOnHidden(e -> stopStream());

        // Populate the container list off the FX thread, then kick off the first load.
        logsArea.setText("Loading…");
        new Thread(() -> {
            try {
                var containers = service.listPodContainers(ctxName, namespace, pod);
                Platform.runLater(() -> {
                    containerBox.setOnAction(null);
                    containerBox.setItems(FXCollections.observableArrayList(containers));
                    String pick = containers.contains(initialContainer) ? initialContainer
                            : (containers.isEmpty() ? null : containers.get(0));
                    containerBox.setValue(pick);
                    containerBox.setOnAction(ev -> loadLogs());
                    loadLogs();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> logsArea.setText("Could not list containers: " + ex.getMessage()));
            }
        }, "kubedesk-logwin-containers-" + pod).start();
    }

    public void show() {
        stage.show();
    }

    private void loadLogs() {
        stopStream();
        String container = containerBox.getValue();
        int tail = tailBox.getValue() != null ? tailBox.getValue() : 500;
        final int gen = ++streamGen;

        if (follow.isSelected()) {
            logsArea.clear();
            try {
                logStream = service.followLogs(ctxName, namespace, pod, container, tail,
                        chunk -> Platform.runLater(() -> {
                            if (gen != streamGen) {
                                return; // a newer load superseded this stream
                            }
                            logsArea.appendText(chunk);
                            logsArea.setScrollTop(Double.MAX_VALUE);
                        }),
                        err -> Platform.runLater(() -> {
                            if (gen == streamGen) {
                                logsArea.appendText("\n[log stream ended"
                                        + (err != null ? ": " + err.getMessage() : "") + "]\n");
                                logsArea.setScrollTop(Double.MAX_VALUE);
                            }
                        }));
            } catch (Exception e) {
                follow.setSelected(false);
                logsArea.setText("Could not follow logs: " + e.getMessage());
            }
            return;
        }

        logsArea.setText("Loading logs…");
        new Thread(() -> {
            try {
                String logs = service.podLogs(ctxName, namespace, pod, container, tail);
                Platform.runLater(() -> {
                    if (gen != streamGen) {
                        return;
                    }
                    logsArea.setText(logs);
                    logsArea.positionCaret(logs.length());
                    logsArea.setScrollTop(Double.MAX_VALUE);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (gen == streamGen) {
                        logsArea.setText("Error: " + ex.getMessage());
                    }
                });
            }
        }, "kubedesk-logwin-" + pod).start();
    }

    private void stopStream() {
        if (logStream != null) {
            try {
                logStream.close();
            } catch (Exception ignored) {
                // best-effort
            }
            logStream = null;
        }
    }

    private void saveLogs() {
        String content = logsArea.getText();
        if (content == null || content.isBlank()) {
            return;
        }
        String container = containerBox.getValue();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String suggested = (pod + (container != null && !container.isBlank() ? "_" + container : "")
                + "_" + stamp + ".log").replaceAll("[^a-zA-Z0-9._-]", "_");

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save pod logs");
        chooser.setInitialFileName(suggested);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Log file (*.log)", "*.log"),
                new FileChooser.ExtensionFilter("Text file (*.txt)", "*.txt"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        var file = chooser.showSaveDialog(stage);
        if (file != null) {
            try {
                Files.writeString(file.toPath(), content);
            } catch (IOException ex) {
                logsArea.appendText("\n[could not save: " + ex.getMessage() + "]\n");
            }
        }
    }
}
