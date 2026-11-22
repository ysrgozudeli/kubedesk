package com.kubedesk.ui;

import com.kubedesk.service.ClusterService;
import com.kubedesk.service.ExecSession;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A standalone, detachable shell window for one pod container. It owns its own TTY exec session
 * over the shared {@link ClusterService}, so it keeps working while you navigate the main window.
 * Several can be open at once; each holds its own connection and frees it when the window closes.
 */
public final class ShellWindow implements OpenWindows.Handle {

    private final ClusterService service;
    private final String ctxName;
    private final String namespace;
    private final String pod;
    private final OpenWindows registry;

    private final Stage stage = new Stage();
    private final ComboBox<String> containerBox = new ComboBox<>();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Disconnected");
    private TerminalGrid terminal;
    private TextArea consoleArea;
    private TextField consoleInput;
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final RadioButton ttyRadio = new RadioButton("Terminal");
    private final RadioButton consoleRadio = new RadioButton("Simple");

    private ExecSession execSession;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Scene scene;
    private boolean ttyView = true; // which view is showing (also routes output that arrives before connect finishes)
    /** True when the remote side (pod) closed the session, as opposed to the user clicking Disconnect. */
    private boolean remoteClosed;

    private final ObjectProperty<OpenWindows.State> state = new SimpleObjectProperty<>(OpenWindows.State.IDLE);

    public ShellWindow(ClusterService service, String ctxName, String namespace, String pod,
                       String initialContainer, OpenWindows registry) {
        this.service = service;
        this.ctxName = ctxName;
        this.namespace = namespace;
        this.pod = pod;
        this.registry = registry;
        build(initialContainer);
    }

    private void build(String initialContainer) {
        containerBox.setMinWidth(150);

        ttyRadio.setToggleGroup(modeGroup);
        consoleRadio.setToggleGroup(modeGroup);
        ttyRadio.setSelected(true);
        HBox modeGroupBox = new HBox(6, ttyRadio, consoleRadio);
        modeGroupBox.setAlignment(Pos.CENTER_LEFT);

        connectBtn.getStyleClass().add("accent-button");
        connectBtn.setOnAction(e -> toggle());

        Button copyBtn = new Button("Copy");
        copyBtn.getStyleClass().add("icon-button");
        copyBtn.setTooltip(new Tooltip("Copy the terminal contents to the clipboard"));
        copyBtn.setOnAction(e -> {
            String text = terminal != null && terminal.isVisible()
                    ? terminal.toText()
                    : (consoleArea != null ? consoleArea.getText() : "");
            if (text != null && !text.isEmpty()) {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(text);
                Clipboard.getSystemClipboard().setContent(cc);
            }
        });

        ttyRadio.setTooltip(new Tooltip("A real terminal (TTY): live prompt, history, vim/top/less. Click it to type."));
        consoleRadio.setTooltip(new Tooltip("Type whole commands in the line below. Interactive programs won't work."));
        HBox containerGroup = new HBox(6, new Label("Container:"), containerBox);
        containerGroup.setAlignment(Pos.CENTER_LEFT);
        statusLabel.getStyleClass().add("muted");
        // FlowPane wraps to a second row in narrow windows instead of clipping.
        FlowPane controls = new FlowPane(8, 6, containerGroup, modeGroupBox, connectBtn, copyBtn, statusLabel);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setRowValignment(javafx.geometry.VPos.CENTER);
        controls.setPadding(new Insets(8, 12, 8, 12));

        // TTY terminal
        terminal = new TerminalGrid();
        VBox.setVgrow(terminal, Priority.ALWAYS);
        terminal.setFocusTraversable(true);
        terminal.setOnGridResize((c, r) -> sendResize());
        terminal.setOnPaste(txt -> { if (execSession != null) execSession.sendRaw(txt.getBytes(StandardCharsets.UTF_8)); });

        // Console area
        consoleArea = new TextArea();
        consoleArea.setEditable(false);
        consoleArea.setWrapText(false);
        consoleArea.getStyleClass().addAll("yaml-area", "logs-area");
        VBox.setVgrow(consoleArea, Priority.ALWAYS);

        consoleInput = new TextField();
        consoleInput.setPromptText("Type a command and press Enter…");
        consoleInput.getStyleClass().add("shell-input");
        consoleInput.setOnAction(e -> sendConsoleCommand());

        showView(true); // TTY is the default mode
        VBox content = new VBox(terminal, consoleArea, consoleInput);
        // Without this the content box keeps its preferred height and only fills part of the window.
        VBox.setVgrow(content, Priority.ALWAYS);
        VBox root = new VBox(controls, content);
        root.getStyleClass().add("root-pane");

        scene = new Scene(root, 900, 560);
        var css = getClass().getResource("/styles/app.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        stage.setTitle(pod + " · shell — KubeDesk");
        var icon = getClass().getResourceAsStream("/icons/kubedesk.png");
        if (icon != null) {
            stage.getIcons().add(new javafx.scene.image.Image(icon));
        }
        stage.setOnHidden(e -> {
            registry.unregister(this);
            shutdown();
        });

        // Key capture for TTY
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (execSession != null && execSession.isTty() && terminalFocused()) {
                forwardKey(e);
            }
        });
        scene.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (execSession != null && execSession.isTty() && terminalFocused()) {
                forwardTyped(e);
            }
        });

        // Populate container list
        containerBox.setPromptText("Loading…");
        new Thread(() -> {
            try {
                var containers = service.listPodContainers(ctxName, namespace, pod);
                Platform.runLater(() -> {
                    containerBox.setItems(FXCollections.observableArrayList(containers));
                    String pick = containers.contains(initialContainer) ? initialContainer
                            : (containers.isEmpty() ? null : containers.get(0));
                    containerBox.setValue(pick);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> containerBox.setPromptText("Error loading containers"));
            }
        }, "kubedesk-shellwin-containers-" + pod).start();
    }

    public void show() {
        stage.show();
        registry.register(this);
    }

    // --- OpenWindows.Handle ---------------------------------------------------------------

    @Override public OpenWindows.Kind kind() { return OpenWindows.Kind.SHELL; }
    @Override public String title() { return pod; }
    @Override public String subtitle() { return ctxName + " / " + namespace; }
    @Override public ReadOnlyObjectProperty<OpenWindows.State> stateProperty() {
        return state;
    }
    @Override public void focus() {
        if (!stage.isShowing()) return; // never resurrect a closed window
        if (stage.isIconified()) stage.setIconified(false);
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }
    @Override public void close() {
        stage.hide();
    }

    private void setState(OpenWindows.State s) {
        state.set(s);
    }

    private boolean terminalFocused() {
        if (terminal == null || scene == null) return false;
        javafx.scene.Node focus = scene.getFocusOwner();
        javafx.scene.Node n = focus;
        while (n != null) {
            if (n == terminal) return true;
            n = n.getParent();
        }
        return false;
    }

    private void toggle() {
        if (execSession != null) {
            closeSession();
        } else {
            connect();
        }
    }

    /** Show either the terminal grid (TTY) or the console area + input line. */
    private void showView(boolean tty) {
        ttyView = tty;
        terminal.setVisible(tty);
        terminal.setManaged(tty);
        consoleArea.setVisible(!tty);
        consoleArea.setManaged(!tty);
        consoleInput.setVisible(!tty);
        consoleInput.setManaged(!tty);
    }

    private void connect() {
        String container = containerBox.getValue();
        boolean tty = ttyRadio.isSelected();
        showView(tty); // visible before connecting so the shell's first prompt isn't lost
        if (tty) {
            terminal.write("\u001bc\u001b[3J"); // fresh screen for a new session
        }
        connectBtn.setDisable(true);
        statusLabel.setText("Connecting…");

        javafx.concurrent.Task<ExecSession> task = new javafx.concurrent.Task<>() {
            @Override protected ExecSession call() {
                if (tty) {
                    return service.execTty(ctxName, namespace, pod, container,
                            ShellWindow.this::onOutput, ShellWindow.this::onClosed);
                } else {
                    return service.exec(ctxName, namespace, pod, container,
                            ShellWindow.this::onOutput, ShellWindow.this::onClosed);
                }
            }
        };
        task.setOnSucceeded(e -> {
            connectBtn.setDisable(false);
            ExecSession session = task.getValue();
            if (session == null) return;
            execSession = session;
            remoteClosed = false;
            setState(OpenWindows.State.LIVE);
            connectBtn.setText("Disconnect");
            statusLabel.setText("Connected (" + session.shell() + ")");
            if (session.isTty()) {
                terminal.requestFocus();
                sendResize();
            } else {
                consoleArea.appendText("Connected. Type a command below.\n");
                consoleInput.setDisable(false);
                consoleInput.requestFocus();
            }
        });
        task.setOnFailed(e -> {
            connectBtn.setDisable(false);
            Throwable ex = task.getException();
            statusLabel.setText("Error: " + (ex != null ? ex.getMessage() : "connect failed"));
        });
        pool.submit(task);
    }

    private void closeSession() {
        if (execSession != null) {
            try { execSession.close(); } catch (Exception ignored) {}
            setState(remoteClosed ? OpenWindows.State.ENDED : OpenWindows.State.IDLE);
            execSession = null;
            // Keep the final screen visible and say what happened (don't swap the view away).
            if (ttyView) {
                terminal.write("\r\n[session closed]\r\n");
            } else {
                consoleArea.appendText("\n[session closed]\n");
            }
        }
        connectBtn.setText("Connect");
        connectBtn.setDisable(false);
        statusLabel.setText("Disconnected");
        if (consoleInput != null) {
            consoleInput.setDisable(true);
        }
    }

    private void onOutput(String text) {
        Platform.runLater(() -> {
            if (ttyView && terminal != null) {
                terminal.write(text);
            } else if (consoleArea != null) {
                consoleArea.appendText(text);
                consoleArea.setScrollTop(Double.MAX_VALUE);
            }
        });
    }

    private void onClosed() {
        Platform.runLater(() -> {
            remoteClosed = true;
            closeSession();
        });
    }

    private void sendConsoleCommand() {
        if (execSession == null) return;
        String cmd = consoleInput.getText();
        consoleArea.appendText("$ " + cmd + "\n");
        execSession.send(cmd + "\n");
        consoleInput.clear();
    }

    private void sendResize() {
        if (execSession == null || !execSession.isTty() || terminal == null) return;
        execSession.resize(terminal.getCols(), terminal.getRows());
    }

    private void forwardKey(KeyEvent e) {
        if (execSession != null && execSession.isTty()) {
            terminal.handleKeyPressed(e, execSession::sendRaw);
        }
    }

    private void forwardTyped(KeyEvent e) {
        if (execSession != null && execSession.isTty()) {
            terminal.handleKeyTyped(e, execSession::sendRaw);
        }
    }
    /** Close the window and free resources. */
    public void shutdown() {
        closeSession();
        pool.shutdownNow();
    }
}
