package com.kubedesk.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * An embedded kubectl console — the bottom "terminal" drawer.
 *
 * <p>It runs the user's <em>own</em> {@code kubectl} (found on the system PATH) as a subprocess and
 * streams its output. The active <b>context</b> and <b>namespace</b> selected in the main window are
 * auto-injected on every command (along with KubeDesk's active {@code --kubeconfig}), so what you run
 * here always matches what you see in the UI — change the namespace dropdown and the console follows.
 *
 * <p>This is a <b>command-runner, not a full TTY</b>: interactive commands that need a pseudo-terminal
 * ({@code kubectl edit}, {@code exec -it}, curses TUIs) won't render — use the pod <b>Shell</b> tab for
 * interactive exec. Commands run through the system shell ({@code cmd /c} / {@code sh -c}) so pipes and
 * redirects ({@code | grep}, {@code | jq}, {@code > file}) work as they do in a normal terminal.
 *
 * <p>All of this uses the user's kubectl and their own credentials — no more privileged than the
 * terminal they'd otherwise alt-tab to. The value is purely that it's context-aware and in-app.
 */
public class KubectlConsole extends VBox {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** The active kube.conf KubeDesk is using (may be {@code null} to defer to kubectl's own discovery). */
    private final Supplier<File> kubeConfig;
    /** The active context name, or {@code null} for the kubeconfig's current-context. */
    private final Supplier<String> context;
    /** The active namespace, or {@code null}/blank to not inject one. */
    private final Supplier<String> namespace;

    private final Label header = new Label();
    private final TextArea output = new TextArea();
    private final TextField input = new TextField();
    private final Button runBtn = new Button("Run");
    private final Button stopBtn = new Button("Stop");
    private final Button clearBtn = new Button("Clear");

    // Command history, navigated with ↑/↓ from the input field.
    private final List<String> history = new ArrayList<>();
    private int historyIndex = 0;

    private final ExecutorService pool = Executors.newCachedThreadPool(daemon());
    private volatile Process current;

    // kubectl-detection state (resolved once, in the background, on construction).
    private volatile boolean checked = false;
    private volatile boolean ready = false;
    private volatile String kubectlVersion = "";

    public KubectlConsole(Supplier<File> kubeConfig, Supplier<String> context, Supplier<String> namespace) {
        this.kubeConfig = kubeConfig;
        this.context = context;
        this.namespace = namespace;
        build();
        detectKubectl();
    }

    private void build() {
        getStyleClass().add("kubectl-console");

        header.getStyleClass().add("console-header-text");
        Region hspacer = new Region();
        HBox.setHgrow(hspacer, Priority.ALWAYS);
        clearBtn.getStyleClass().add("icon-button");
        clearBtn.setOnAction(e -> output.clear());
        HBox headerBar = new HBox(10, header, hspacer, clearBtn);
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.setPadding(new Insets(6, 12, 6, 12));
        headerBar.getStyleClass().add("console-header");

        output.setEditable(false);
        output.setWrapText(false);
        output.getStyleClass().addAll("yaml-area", "logs-area");
        VBox.setVgrow(output, Priority.ALWAYS);

        Label prompt = new Label("kubectl ▸");
        prompt.getStyleClass().add("kubectl-prompt");
        input.setPromptText("get pods -o wide       (Enter to run · ↑/↓ history · pipes & redirects work)");
        input.getStyleClass().add("shell-input");
        HBox.setHgrow(input, Priority.ALWAYS);
        input.setOnAction(e -> run());
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.UP) {
                recallHistory(-1);
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN) {
                recallHistory(1);
                e.consume();
            }
        });

        runBtn.getStyleClass().add("accent-button");
        runBtn.setOnAction(e -> run());
        stopBtn.setOnAction(e -> stop());
        stopBtn.setDisable(true);

        HBox inputBar = new HBox(8, prompt, input, runBtn, stopBtn);
        inputBar.setAlignment(Pos.CENTER_LEFT);
        inputBar.setPadding(new Insets(8, 12, 8, 12));

        getChildren().addAll(headerBar, output, inputBar);
        applyEnablement(false);
    }

    // --- kubectl detection ------------------------------------------------------------------

    private void detectKubectl() {
        header.setText("Checking for kubectl…");
        pool.submit(() -> {
            String version = probeVersion();
            Platform.runLater(() -> {
                checked = true;
                ready = version != null;
                kubectlVersion = version != null ? version : "";
                refreshHeader();
                applyEnablement(false);
                if (ready) {
                    output.appendText("kubectl " + kubectlVersion + " ready. "
                            + "Type a kubectl command below (the context & namespace are applied for you).\n\n");
                }
            });
        });
    }

    /** Run {@code kubectl version --client} through the shell; return its client version, or null if absent. */
    private String probeVersion() {
        try {
            Process p = shell("kubectl version --client -o yaml").start();
            String out = readAll(p.getInputStream());
            int code = p.waitFor();
            if (code != 0) {
                return null;
            }
            for (String line : out.split("\\R")) {
                String t = line.trim();
                if (t.startsWith("gitVersion:")) {
                    return t.substring("gitVersion:".length()).trim().replace("\"", "");
                }
            }
            return "ready"; // found, but couldn't parse a version — good enough
        } catch (Exception e) {
            return null;
        }
    }

    // --- running commands -------------------------------------------------------------------

    private void run() {
        if (current != null || !ready) {
            return; // a command is already running, or kubectl isn't available
        }
        String args = input.getText().trim();
        if (args.isEmpty()) {
            return;
        }
        history.add(args);
        historyIndex = history.size();
        input.clear();

        output.appendText("$ kubectl " + args + "\n");
        output.setScrollTop(Double.MAX_VALUE);

        Process p;
        try {
            p = shell(buildCommand(args)).start();
        } catch (Exception e) {
            output.appendText("[failed to launch kubectl: " + e.getMessage() + "]\n\n");
            return;
        }
        current = p;
        applyEnablement(true);
        pool.submit(() -> stream(p));
    }

    /**
     * Compose the full shell command line: {@code kubectl} + KubeDesk's kubeconfig/context/namespace +
     * the user's arguments. Context/namespace injection is skipped when the user already typed the flag,
     * so an explicit {@code -n other} or {@code --all-namespaces} still wins.
     */
    private String buildCommand(String args) {
        StringBuilder sb = new StringBuilder("kubectl");
        File kc = kubeConfig.get();
        if (kc != null) {
            sb.append(" --kubeconfig ").append(quote(kc.getAbsolutePath()));
        }
        String ctx = context.get();
        if (ctx != null && !ctx.isBlank() && !mentions(args, "--context")) {
            sb.append(" --context ").append(quote(ctx));
        }
        String ns = namespace.get();
        if (ns != null && !ns.isBlank() && !mentionsNamespace(args)) {
            sb.append(" --namespace ").append(quote(ns));
        }
        return sb.append(' ').append(args).toString();
    }

    private void stream(Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String l = line;
                Platform.runLater(() -> {
                    output.appendText(l + "\n");
                    output.setScrollTop(Double.MAX_VALUE);
                });
            }
        } catch (Exception ignored) {
            // stream closed (usually because the process was stopped) — the finally block reports exit
        } finally {
            int code = -1;
            try {
                code = p.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            final int c = code;
            Platform.runLater(() -> {
                if (c != 0) {
                    output.appendText("[exit " + c + "]\n");
                }
                output.appendText("\n");
                output.setScrollTop(Double.MAX_VALUE);
                current = null;
                applyEnablement(false);
            });
        }
    }

    /** Stop a running command (e.g. a {@code logs -f} / {@code get -w} that never ends). */
    private void stop() {
        Process p = current;
        if (p == null) {
            return;
        }
        // Through the shell, kubectl is a child of cmd/sh — kill the descendants too, not just the shell.
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        output.appendText("[stopped]\n");
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Called by the host when the drawer becomes visible: refresh the target line and focus the input. */
    public void onShown() {
        refreshHeader();
        if (ready) {
            input.requestFocus();
        }
    }

    /** Destroy any running command and shut the pool down (called on app exit). */
    public void shutdown() {
        Process p = current;
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
        pool.shutdownNow();
    }

    private void refreshHeader() {
        if (!checked) {
            header.setText("Checking for kubectl…");
        } else if (ready) {
            header.setText("⎈ kubectl " + kubectlVersion + "     ·     " + targetLabel());
        } else {
            header.setText("⚠ kubectl not found on PATH — install it to use this console "
                    + "(KubeDesk's own actions don't need it).");
        }
    }

    private String targetLabel() {
        String ctx = context.get();
        String ns = namespace.get();
        return "context: " + (ctx == null || ctx.isBlank() ? "(current)" : ctx)
                + "      namespace: " + (ns == null || ns.isBlank() ? "(default)" : ns);
    }

    /** Enable/disable the controls for the running (or ready) state. */
    private void applyEnablement(boolean running) {
        runBtn.setDisable(running || !ready);
        input.setDisable(running || !ready);
        stopBtn.setDisable(!running);
    }

    private void recallHistory(int dir) {
        if (history.isEmpty()) {
            return;
        }
        historyIndex = Math.max(0, Math.min(history.size(), historyIndex + dir));
        if (historyIndex >= history.size()) {
            input.clear();
        } else {
            input.setText(history.get(historyIndex));
            input.positionCaret(input.getText().length());
        }
    }

    private ProcessBuilder shell(String command) {
        ProcessBuilder pb = WINDOWS
                ? new ProcessBuilder("cmd", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        pb.redirectErrorStream(true); // merge stderr into stdout so errors show inline, in order
        return pb;
    }

    private static boolean mentionsNamespace(String args) {
        return mentions(args, "--namespace") || mentions(args, "--all-namespaces")
                || mentions(args, "-n ") || args.endsWith("-n")
                || mentions(args, "-A ") || args.endsWith("-A");
    }

    private static boolean mentions(String args, String flag) {
        return (" " + args + " ").contains(" " + flag);
    }

    /** Quote a value for the shell so paths/names with spaces survive. Works for both cmd and sh. */
    private static String quote(String s) {
        return "\"" + s.replace("\"", "") + "\"";
    }

    private static String readAll(InputStream in) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private static ThreadFactory daemon() {
        return r -> {
            Thread t = new Thread(r, "kubedesk-kubectl");
            t.setDaemon(true);
            return t;
        };
    }
}
