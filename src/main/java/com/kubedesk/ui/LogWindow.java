package com.kubedesk.ui;

import com.kubedesk.service.ClusterService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
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
    private final TailScroller logTail = new TailScroller(logsArea);
    private final LogBuffer logBuffer = new LogBuffer(logsArea, logTail);
    private final ToggleButton follow = new ToggleButton("▶");
    private final CheckBox previousCheck = new CheckBox("Previous");
    private final CheckBox timestampsCheck = new CheckBox("Timestamps");
    private final Label statusDot = new Label("●");

    private AutoCloseable logStream;
    /** Bumped on each (re)load so callbacks from a superseded stream can be ignored. */
    private int streamGen;

    public LogWindow(ClusterService service, String ctxName, String namespace, String pod,
                     String initialContainer, int initialTail, boolean startFollowing, String status) {
        this.service = service;
        this.ctxName = ctxName;
        this.namespace = namespace;
        this.pod = pod;
        build(initialContainer, initialTail, startFollowing, status);
    }

    private void build(String initialContainer, int initialTail, boolean startFollowing, String status) {
        // Pod-status dot (green Running / amber Pending / red failing), set from the opener's status.
        statusDot.getStyleClass().add("status-dot");
        if (status == null || status.isBlank()) {
            statusDot.setVisible(false);
        } else {
            statusDot.getStyleClass().add(dotClassFor(status));
            statusDot.setTooltip(new Tooltip(status));
        }

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
            String text = logBuffer.raw();
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

        // Filter lines (case-insensitive, hides non-matching lines from view only).
        TextField filterField = new TextField();
        filterField.setPromptText("Filter lines…");
        filterField.setPrefColumnCount(14);
        filterField.textProperty().addListener((o, a, b) -> logBuffer.setFilter(b));

        ToggleButton wrap = new ToggleButton("⤶");
        wrap.getStyleClass().add("log-follow");
        wrap.setTooltip(new Tooltip("Wrap long lines"));
        logsArea.wrapTextProperty().bind(wrap.selectedProperty());

        Button clear = new Button("⌫");
        clear.getStyleClass().add("icon-button");
        clear.setTooltip(new Tooltip("Clear the log view"));
        clear.setOnAction(e -> logBuffer.clear());

        previousCheck.setTooltip(new Tooltip("Show the previous (crashed/restarted) container's logs"));
        previousCheck.setOnAction(e -> loadLogs());
        follow.disableProperty().bind(previousCheck.selectedProperty());

        timestampsCheck.setTooltip(new Tooltip("Prefix each line with the server timestamp"));
        timestampsCheck.setOnAction(e -> loadLogs());

        // A FlowPane so the toolbar wraps to a second line on a narrow window instead of clipping.
        HBox containerGroup = new HBox(6, new Label("Container:"), containerBox);
        containerGroup.setAlignment(Pos.CENTER_LEFT);
        HBox tailGroup = new HBox(6, new Label("Tail:"), tailBox);
        tailGroup.setAlignment(Pos.CENTER_LEFT);

        FlowPane controls = new FlowPane(8, 8,
                statusDot, containerGroup, tailGroup, refresh, follow, previousCheck, timestampsCheck,
                filterField, wrap, clear, copy, save);
        controls.getStyleClass().add("log-toolbar");
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8, 12, 8, 12));

        logsArea.setEditable(false);
        logsArea.getStyleClass().addAll("yaml-area", "logs-area");

        Button jump = new Button("↓ Jump to latest");
        jump.getStyleClass().add("jump-latest");
        jump.setOnAction(e -> logTail.scrollToBottom());
        jump.visibleProperty().bind(logTail.atBottomProperty().not());
        jump.managedProperty().bind(jump.visibleProperty());
        StackPane.setAlignment(jump, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(jump, new Insets(0, 16, 16, 0));

        StackPane logStack = new StackPane(logsArea, jump);
        VBox.setVgrow(logStack, Priority.ALWAYS);

        VBox root = new VBox(controls, logStack);
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
        boolean previous = previousCheck.isSelected();
        boolean timestamps = timestampsCheck.isSelected();
        final int gen = ++streamGen;

        if (follow.isSelected() && !previous) {
            logBuffer.clear();
            try {
                logStream = service.followLogs(ctxName, namespace, pod, container, tail, timestamps,
                        chunk -> Platform.runLater(() -> {
                            if (gen != streamGen) {
                                return; // a newer load superseded this stream
                            }
                            logBuffer.append(chunk);
                        }),
                        err -> Platform.runLater(() -> {
                            if (gen == streamGen) {
                                logBuffer.append("\n[log stream ended"
                                        + (err != null ? ": " + err.getMessage() : "") + "]\n");
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
                String logs = service.podLogs(ctxName, namespace, pod, container, tail, previous, timestamps);
                Platform.runLater(() -> {
                    if (gen != streamGen) {
                        return;
                    }
                    logBuffer.setText(logs);
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

    /** Map a pod status/phase string to a status-dot CSS class (mirrors the table's status chips). */
    private static String dotClassFor(String status) {
        String v = status.toLowerCase();
        if (v.contains("running") || v.contains("ready") || v.contains("succeeded") || v.contains("complete")) {
            return "dot-ok";
        }
        if (v.contains("pending") || v.contains("containercreating") || v.contains("terminating")) {
            return "dot-warn";
        }
        if (v.contains("fail") || v.contains("error") || v.contains("crash") || v.contains("backoff")
                || v.contains("oom") || v.contains("evict") || v.contains("imagepull")) {
            return "dot-bad";
        }
        return "dot-neutral";
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
        String content = logBuffer.raw();
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
