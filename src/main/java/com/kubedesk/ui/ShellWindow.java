package com.kubedesk.ui;

import com.kubedesk.service.ClusterService;
import com.kubedesk.service.ExecSession;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
public final class ShellWindow {

    private final ClusterService service;
    private final String ctxName;
    private final String namespace;
    private final String pod;

    private final Stage stage = new Stage();
    private final ComboBox<String> containerBox = new ComboBox<>();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Disconnected");
    private TerminalGrid terminal;
    private TextArea consoleArea;
    private TextField consoleInput;
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final RadioButton ttyRadio = new RadioButton("TTY");
    private final RadioButton consoleRadio = new RadioButton("Console");

    private ExecSession execSession;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Scene scene;

    public ShellWindow(ClusterService service, String ctxName, String namespace, String pod,
                       String initialContainer) {
        this.service = service;
        this.ctxName = ctxName;
        this.namespace = namespace;
        this.pod = pod;
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

        HBox controls = new HBox(8, new Label("Container:"), containerBox,
                modeGroupBox, connectBtn, copyBtn, statusLabel);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8, 12, 8, 12));

        // TTY terminal
        terminal = new TerminalGrid(100, 30);
        VBox.setVgrow(terminal, Priority.ALWAYS);
        terminal.setFocusTraversable(true);
        terminal.setOnMouseClicked(e -> terminal.requestFocus());
        terminal.widthProperty().addListener((o, a, b) -> sendResize());
        terminal.heightProperty().addListener((o, a, b) -> sendResize());
        terminal.setVisible(false);
        terminal.setManaged(false);

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

        VBox content = new VBox(terminal, consoleArea, consoleInput);
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
        stage.setOnHidden(e -> closeSession());

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

    private void connect() {
        String container = containerBox.getValue();
        boolean tty = ttyRadio.isSelected();
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
            connectBtn.setText("Disconnect");
            statusLabel.setText("Connected (" + session.shell() + ")");
            if (session.isTty()) {
                terminal.setVisible(true);
                terminal.setManaged(true);
                consoleArea.setVisible(false);
                consoleArea.setManaged(false);
                consoleInput.setVisible(false);
                consoleInput.setManaged(false);
                terminal.requestFocus();
                sendResize();
            } else {
                terminal.setVisible(false);
                terminal.setManaged(false);
                consoleArea.setVisible(true);
                consoleArea.setManaged(true);
                consoleInput.setVisible(true);
                consoleInput.setManaged(true);
                consoleArea.appendText("Connected. Type a command below.\n");
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
            execSession = null;
        }
        connectBtn.setText("Connect");
        connectBtn.setDisable(false);
        statusLabel.setText("Disconnected");
        if (terminal != null) {
            terminal.setVisible(false);
            terminal.setManaged(false);
        }
        if (consoleArea != null) {
            consoleArea.setVisible(true);
            consoleArea.setManaged(true);
        }
        if (consoleInput != null) {
            consoleInput.setVisible(true);
            consoleInput.setManaged(true);
            consoleInput.setDisable(true);
        }
    }

    private void onOutput(String text) {
        Platform.runLater(() -> {
            if (execSession != null && execSession.isTty() && terminal != null) {
                terminal.write(text.getBytes(StandardCharsets.UTF_8));
            } else if (consoleArea != null) {
                consoleArea.appendText(text);
                consoleArea.setScrollTop(Double.MAX_VALUE);
            }
        });
    }

    private void onClosed() {
        Platform.runLater(this::closeSession);
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
        double w = terminal.getWidth();
        double h = terminal.getHeight();
        if (w < 20 || h < 20) return;
        int cols = Math.max(2, (int) (w / TerminalGrid.CELL_W));
        int rows = Math.max(1, (int) (h / TerminalGrid.CELL_H));
        execSession.resize(cols, rows);
    }

    private void forwardKey(KeyEvent e) {
        ExecSession s = execSession;
        if (s == null || !s.isTty()) return;
        KeyCode code = e.getCode();
        if (code == null) return;
        boolean ctrl = e.isControlDown();

        switch (code) {
            case ENTER: s.sendRaw(new byte[]{0x0d}); e.consume(); return;
            case BACK_SPACE: s.sendRaw(new byte[]{0x7f}); e.consume(); return;
            case TAB: s.sendRaw(new byte[]{0x09}); e.consume(); return;
            case ESCAPE: s.sendRaw(new byte[]{0x1b}); e.consume(); return;
            case UP: s.sendRaw(new byte[]{0x1b, 0x5b, 0x41}); e.consume(); return;
            case DOWN: s.sendRaw(new byte[]{0x1b, 0x5b, 0x42}); e.consume(); return;
            case RIGHT: s.sendRaw(new byte[]{0x1b, 0x5b, 0x43}); e.consume(); return;
            case LEFT: s.sendRaw(new byte[]{0x1b, 0x5b, 0x44}); e.consume(); return;
            case HOME: s.sendRaw(new byte[]{0x1b, 0x5b, 0x48}); e.consume(); return;
            case END: s.sendRaw(new byte[]{0x1b, 0x5b, 0x46}); e.consume(); return;
            case PAGE_UP: s.sendRaw(new byte[]{0x1b, 0x5b, 0x35, 0x3b, 0x32}); e.consume(); return;
            case PAGE_DOWN: s.sendRaw(new byte[]{0x1b, 0x5b, 0x36, 0x3b, 0x32}); e.consume(); return;
            case DELETE: s.sendRaw(new byte[]{0x1b, 0x5b, 0x33}); e.consume(); return;
            default: break;
        }

        if (ctrl && code.isLetterKey()) {
            int c = Character.toLowerCase(code.getName().charAt(code.getName().length() - 1));
            s.sendRaw(new byte[]{(byte) ((c - 'a') + 1)});
            e.consume();
        }
    }

    private void forwardTyped(KeyEvent e) {
        ExecSession s = execSession;
        if (s == null || !s.isTty() || e.isControlDown() || e.isMetaDown()) return;
        String ch = e.getCharacter();
        if (ch != null && !ch.isEmpty()
                && ch.chars().allMatch(c -> c >= 0x20 && c != 0x7f)) {
            s.sendRaw(ch.getBytes(StandardCharsets.UTF_8));
            e.consume();
        }
    }

    /** Close the window and free resources. */
    public void shutdown() {
        closeSession();
        pool.shutdownNow();
    }
}
