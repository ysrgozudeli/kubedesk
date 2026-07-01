package com.kubedesk.ui;

import com.kubedesk.service.ClusterService;
import com.kubedesk.service.ExecSession;
import com.kubedesk.service.Dtos.ContextInfo;
import com.kubedesk.service.Dtos.HealthSummary;
import com.kubedesk.service.Dtos.ResourceData;
import com.kubedesk.service.Fabric8ClusterService;
import com.kubedesk.service.ResourceKind;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.util.Duration;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The whole KubeDesk UI: a Kubernetes-Dashboard-style shell with a top bar (kube.conf picker +
 * context/namespace selectors), a sidebar of resource kinds, a pod-health ring, and a dynamic table.
 */
public class MainView extends BorderPane {

    private final ClusterService service = new Fabric8ClusterService();

    // Top bar controls
    private final Label kubeConfigLabel = new Label("(default ~/.kube/config)");
    private final ComboBox<ContextInfo> contextBox = new ComboBox<>();
    private final ComboBox<String> namespaceBox = new ComboBox<>();
    private final TextField searchField = new TextField();

    // Content
    private final SplitPane centerSplit = new SplitPane();
    private final TableView<List<String>> table = new TableView<>();
    private final HBox healthRing = new HBox();
    private final Label contentTitle = new Label("Pods");
    private final Label statusBar = new Label("Ready.");

    // Details panel (right side)
    private final VBox detailsPane = new VBox();
    private final Label detailsTitle = new Label();
    private final Label detailsSubtitle = new Label();
    private final VBox overviewBox = new VBox(6);
    private final TextArea yamlArea = new TextArea();
    private final TabPane detailsTabs = new TabPane();
    private final TextArea eventsArea = new TextArea();
    private final Tab overviewTab = new Tab("Overview", overviewBox);
    private final Tab yamlTab = new Tab("YAML", yamlArea);
    private final Tab eventsTab = new Tab("Events", eventsArea);
    private final Tab logsTab = new Tab("Logs");
    private final Tab shellTab = new Tab("Shell");

    // Logs view controls
    private final ComboBox<String> containerBox = new ComboBox<>();
    private final ComboBox<Integer> tailBox = new ComboBox<>();
    private final TextArea logsArea = new TextArea();
    private String currentLogPod;

    // Shell (exec) controls
    private final ComboBox<String> shellContainerBox = new ComboBox<>();
    private final TextArea shellArea = new TextArea();
    private final TextField shellInput = new TextField();
    private final Button shellConnectBtn = new Button("Connect");
    private ExecSession execSession;
    private String shellPod;
    /** Name of the resource currently shown in the details panel (for YAML reload/apply). */
    private String currentDetailName;

    private ResourceKind currentKind = ResourceKind.PODS;
    private ResourceData currentData = new ResourceData(List.of(), List.of());

    /** Per-kind set of column names the user has hidden via the table's column menu. */
    private final Map<ResourceKind, Set<String>> hiddenColumns = new EnumMap<>(ResourceKind.class);

    // Live updates
    private final Label liveLabel = new Label("● Live");
    private final Button pauseButton = new Button("⏸");
    private final HBox liveBox = new HBox(6);
    private boolean paused = false;
    private AutoCloseable currentWatch;
    /** Coalesces bursts of watch events into a single re-list. */
    private final PauseTransition autoRefreshPause = new PauseTransition(Duration.millis(400));

    private final java.util.concurrent.ExecutorService pool =
            Executors.newCachedThreadPool(daemonThreads());

    public MainView() {
        getStyleClass().add("root-pane");
        buildDetails(); // builds detailsPane (added to the split only when shown)
        centerSplit.getItems().setAll(buildContent());
        setTop(buildTopBar());
        setLeft(buildSidebar());
        setCenter(centerSplit);
        setBottom(buildStatusBar());
    }

    /** Kick off the first load once the scene is shown. */
    public void init() {
        // When a burst of watch events settles, re-list the current view once.
        autoRefreshPause.setOnFinished(e -> refreshContent());
        loadContexts();
    }

    // --- top bar ----------------------------------------------------------------------------

    private HBox buildTopBar() {
        Label brand = new Label("KubeDesk");
        brand.getStyleClass().add("brand");

        Button loadConfig = new Button("Load kube.conf…");
        loadConfig.getStyleClass().add("accent-button");
        loadConfig.setOnAction(e -> chooseKubeConfig());

        kubeConfigLabel.getStyleClass().add("muted");

        contextBox.setPromptText("Context");
        contextBox.setMinWidth(220);
        contextBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ContextInfo c) {
                return c == null ? "" : (c.current() ? "● " : "") + c.name();
            }
            @Override public ContextInfo fromString(String s) { return null; }
        });
        contextBox.setOnAction(e -> onContextChosen());

        namespaceBox.setPromptText("Namespace");
        namespaceBox.setMinWidth(180);
        namespaceBox.setOnAction(e -> onSelectionChanged());

        searchField.setPromptText("Search…");
        searchField.setMinWidth(180);
        searchField.textProperty().addListener((o, a, b) -> applyFilter());

        Button refresh = new Button("↻");
        refresh.getStyleClass().add("icon-button");
        refresh.setOnAction(e -> refreshContent());

        liveLabel.getStyleClass().add("live-indicator");
        pauseButton.getStyleClass().add("live-toggle");
        pauseButton.setOnAction(e -> togglePause());
        pauseButton.setTooltip(new Tooltip("Pause live updates"));
        liveBox.setAlignment(Pos.CENTER_LEFT);
        liveBox.getChildren().setAll(liveLabel, pauseButton);
        liveBox.setVisible(false);
        liveBox.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(12,
                brand,
                new VSep(),
                loadConfig, kubeConfigLabel,
                spacer,
                liveBox,
                new Label("Context:"), contextBox,
                new Label("Namespace:"), namespaceBox,
                searchField, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 16, 10, 16));
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    private void chooseKubeConfig() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select a kube.conf file");
        File start = new File(System.getProperty("user.home"), ".kube");
        if (start.isDirectory()) {
            chooser.setInitialDirectory(start);
        }
        File chosen = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (chosen != null) {
            ((Fabric8ClusterService) service).setKubeConfigFile(chosen);
            kubeConfigLabel.setText(chosen.getName());
            status("Loaded kube.conf: " + chosen.getAbsolutePath());
            loadContexts();
        }
    }

    // --- sidebar ----------------------------------------------------------------------------

    private VBox buildSidebar() {
        VBox nav = new VBox(4);
        nav.getStyleClass().add("sidebar");
        nav.setPadding(new Insets(16, 8, 16, 8));
        nav.setMinWidth(190);

        ToggleGroup group = new ToggleGroup();
        String lastSection = null;
        for (ResourceKind kind : ResourceKind.values()) {
            if (!kind.section().equals(lastSection)) {
                Label section = new Label(kind.section().toUpperCase());
                section.getStyleClass().add("nav-section");
                nav.getChildren().add(section);
                lastSection = kind.section();
            }
            ToggleButton btn = new ToggleButton("›  " + kind.label());
            btn.setToggleGroup(group);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.getStyleClass().add("nav-item");
            btn.setUserData(kind);
            if (kind == currentKind) {
                btn.setSelected(true);
            }
            btn.setOnAction(e -> {
                if (btn.isSelected()) {
                    currentKind = kind;
                    contentTitle.setText(kind.label());
                    closeDetails();
                    onSelectionChanged();
                }
            });
            nav.getChildren().add(btn);
        }

        // Pin the global "+ Create" action to the bottom of the sidebar (it applies any kind).
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        Button createBtn = new Button("+ Create");
        createBtn.getStyleClass().add("accent-button");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setOnAction(e -> openCreateDialog());
        VBox createBox = new VBox(createBtn);
        createBox.setPadding(new Insets(10, 4, 0, 4));
        nav.getChildren().addAll(grow, createBox);
        return nav;
    }

    // --- center content ---------------------------------------------------------------------

    private VBox buildContent() {
        contentTitle.getStyleClass().add("content-title");

        healthRing.setSpacing(16);
        healthRing.setAlignment(Pos.CENTER_LEFT);
        healthRing.setPadding(new Insets(4, 0, 8, 0));

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No resources to display."));
        table.setRowFactory(tv -> buildRow());
        // The "+" button at the top-right of the header lets the user show/hide any column.
        table.setTableMenuButtonVisible(true);
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox content = new VBox(12, contentTitle, healthRing, table);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("content");
        content.setMinWidth(320); // keep the table usable no matter how wide the details pane gets
        return content;
    }

    private HBox buildStatusBar() {
        HBox box = new HBox(statusBar);
        box.setPadding(new Insets(6, 16, 6, 16));
        box.getStyleClass().add("status-bar");
        return box;
    }

    // --- row interactions (context menu + double-click) -------------------------------------

    /** A table row wired with a right-click menu and a double-click-to-open-details gesture. */
    private TableRow<List<String>> buildRow() {
        TableRow<List<String>> row = new TableRow<>();

        MenuItem details = new MenuItem("View details");
        details.setOnAction(e -> showDetails(row.getItem(), overviewTab));

        MenuItem yaml = new MenuItem("View YAML");
        yaml.setOnAction(e -> showDetails(row.getItem(), yamlTab));

        MenuItem events = new MenuItem("View events");
        events.setOnAction(e -> showDetails(row.getItem(), eventsTab));

        MenuItem logs = new MenuItem("View logs");
        logs.setOnAction(e -> showDetails(row.getItem(), logsTab));

        MenuItem copyName = new MenuItem("Copy name");
        copyName.setOnAction(e -> copyToClipboard(resourceName(row.getItem())));

        MenuItem copyYaml = new MenuItem("Copy YAML");
        copyYaml.setOnAction(e -> copyYaml(row.getItem()));

        // Mutating actions
        MenuItem restart = new MenuItem("Restart (rollout)");
        restart.setOnAction(e -> restartAction(row.getItem()));

        MenuItem scale = new MenuItem("Scale…");
        scale.setOnAction(e -> scaleAction(row.getItem()));

        MenuItem delete = new MenuItem("Delete");
        delete.getStyleClass().add("danger-item");
        delete.setOnAction(e -> deleteAction(row.getItem()));

        ContextMenu menu = new ContextMenu(details, yaml, events, logs,
                new SeparatorMenuItem(), copyName, copyYaml,
                new SeparatorMenuItem(), restart, scale, delete);
        menu.setOnShowing(e -> {
            // Tailor the menu to the resource kind: hide (not just disable) what doesn't apply.
            logs.setVisible(currentKind == ResourceKind.PODS);
            events.setVisible(kindHasEvents(currentKind));
            boolean isDeployment = currentKind == ResourceKind.DEPLOYMENTS;
            restart.setVisible(isDeployment);
            scale.setVisible(isDeployment);
        });
        // Only show the menu on non-empty rows.
        row.contextMenuProperty().bind(
                Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(menu));

        row.setOnMouseClicked(ev -> {
            if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 2 && !row.isEmpty()) {
                showDetails(row.getItem(), overviewTab);
            }
        });
        return row;
    }

    // --- details panel ----------------------------------------------------------------------

    private VBox buildDetails() {
        detailsPane.getStyleClass().add("details");
        detailsPane.setMinWidth(360); // floor; actual width is set by the draggable split divider

        Button close = new Button("✕");
        close.getStyleClass().add("icon-button");
        close.setOnAction(e -> closeDetails());

        detailsTitle.getStyleClass().add("details-title");
        detailsSubtitle.getStyleClass().add("muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox titleBox = new VBox(2, detailsTitle, detailsSubtitle);
        HBox header = new HBox(8, titleBox, spacer, close);
        header.setAlignment(Pos.TOP_LEFT);
        header.setPadding(new Insets(14, 14, 8, 16));
        header.getStyleClass().add("details-header");

        overviewBox.setPadding(new Insets(12, 16, 12, 16));

        yamlArea.setEditable(true);
        yamlArea.setWrapText(false);
        yamlArea.getStyleClass().add("yaml-area");

        eventsArea.setEditable(false);
        eventsArea.setWrapText(true);
        eventsArea.getStyleClass().add("yaml-area");

        overviewTab.setClosable(false);
        yamlTab.setClosable(false);
        yamlTab.setContent(buildYamlPane());
        eventsTab.setClosable(false);
        logsTab.setClosable(false);
        logsTab.setContent(buildLogsPane());
        shellTab.setClosable(false);
        shellTab.setContent(buildShellPane());
        detailsTabs.getTabs().setAll(overviewTab, yamlTab, eventsTab);
        VBox.setVgrow(detailsTabs, Priority.ALWAYS);

        detailsPane.getChildren().setAll(header, detailsTabs);
        return detailsPane;
    }

    /** The YAML tab content: an editable manifest with Apply / Reload. */
    private VBox buildYamlPane() {
        Button apply = new Button("Apply");
        apply.getStyleClass().add("accent-button");
        apply.setOnAction(e -> applyYamlAction());

        Button reload = new Button("Reload");
        reload.getStyleClass().add("icon-button");
        reload.setOnAction(e -> reloadYaml());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label hint = new Label("Editable — edits are applied only when you click Apply.");
        hint.getStyleClass().add("muted");

        HBox controls = new HBox(8, hint, spacer, reload, apply);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8, 12, 8, 12));

        VBox.setVgrow(yamlArea, Priority.ALWAYS);
        return new VBox(controls, yamlArea);
    }

    /** The Shell tab: a command console into a pod container (connect, type commands, see output). */
    private VBox buildShellPane() {
        shellContainerBox.setMinWidth(150);

        shellConnectBtn.getStyleClass().add("accent-button");
        shellConnectBtn.setOnAction(e -> toggleShell());

        Label note = new Label("Command console (not a full TTY — no vim/top).");
        note.getStyleClass().add("muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox controls = new HBox(8,
                new Label("Container:"), shellContainerBox, shellConnectBtn, spacer, note);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8, 12, 8, 12));

        shellArea.setEditable(false);
        shellArea.setWrapText(false);
        shellArea.getStyleClass().addAll("yaml-area", "logs-area");
        VBox.setVgrow(shellArea, Priority.ALWAYS);

        shellInput.setPromptText("Type a command and press Enter…");
        shellInput.getStyleClass().add("shell-input");
        shellInput.setDisable(true);
        shellInput.setOnAction(e -> sendShellCommand());

        VBox pane = new VBox(controls, shellArea, shellInput);
        return pane;
    }

    private void toggleShell() {
        if (execSession != null) {
            closeExec();
        } else {
            connectShell();
        }
    }

    private void connectShell() {
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null || shellPod == null) {
            return;
        }
        String container = shellContainerBox.getValue();
        shellArea.appendText("Connecting to " + shellPod
                + (container != null ? " [" + container + "]" : "") + " …\n");
        try {
            execSession = service.exec(ctx.name(), namespaceBox.getValue(), shellPod, container,
                    this::appendShellOutput, this::onExecClosed);
            shellConnectBtn.setText("Disconnect");
            shellInput.setDisable(false);
            shellInput.requestFocus();
        } catch (Exception e) {
            shellArea.appendText("[failed to connect: " + e.getMessage() + "]\n");
        }
    }

    private void sendShellCommand() {
        if (execSession == null) {
            return;
        }
        String cmd = shellInput.getText();
        shellArea.appendText("$ " + cmd + "\n"); // echo, since we run without a TTY
        execSession.send(cmd + "\n");
        shellInput.clear();
    }

    private void appendShellOutput(String text) {
        Platform.runLater(() -> {
            shellArea.appendText(text);
            shellArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private void onExecClosed() {
        Platform.runLater(() -> {
            shellArea.appendText("\n[session closed]\n");
            execSession = null;
            shellConnectBtn.setText("Connect");
            shellInput.setDisable(true);
        });
    }

    /** Close any active shell session (on pod change, panel close, or app shutdown). */
    private void closeExec() {
        if (execSession != null) {
            try {
                execSession.close();
            } catch (Exception ignored) {
                // best-effort
            }
            execSession = null;
        }
        shellConnectBtn.setText("Connect");
        shellInput.setDisable(true);
    }

    private void reloadYaml() {
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null || currentDetailName == null) {
            return;
        }
        yamlArea.setText("Loading…");
        runAsync(() -> service.describeYaml(ctx.name(), namespaceBox.getValue(), currentKind, currentDetailName),
                yamlArea::setText);
    }

    /** The Logs tab content: container picker + tail-size picker + refresh + a monospace log view. */
    private VBox buildLogsPane() {
        containerBox.setMinWidth(150);
        containerBox.setOnAction(e -> loadLogs());

        tailBox.setItems(FXCollections.observableArrayList(100, 500, 1000, 5000));
        tailBox.setValue(500);
        tailBox.setOnAction(e -> loadLogs());

        Button refresh = new Button("↻");
        refresh.getStyleClass().add("icon-button");
        refresh.setOnAction(e -> loadLogs());

        Button save = new Button("Save…");
        save.getStyleClass().add("accent-button");
        save.setOnAction(e -> saveLogs());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox controls = new HBox(8,
                new Label("Container:"), containerBox,
                new Label("Tail:"), tailBox,
                refresh, spacer, save);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8, 12, 8, 12));

        logsArea.setEditable(false);
        logsArea.setWrapText(false);
        logsArea.getStyleClass().addAll("yaml-area", "logs-area");
        VBox.setVgrow(logsArea, Priority.ALWAYS);

        VBox pane = new VBox(controls, logsArea);
        return pane;
    }

    /** Populate and reveal the details panel for the given row, selecting the given tab. */
    private void showDetails(List<String> row, Tab target) {
        if (row == null) {
            return;
        }
        String name = resourceName(row);
        currentDetailName = name;
        detailsTitle.setText(name);
        detailsSubtitle.setText(currentKind.label() + (currentKind.namespaced()
                ? " · " + safeNamespace() : ""));

        // Overview: column → value pairs straight from the loaded row.
        overviewBox.getChildren().clear();
        List<String> cols = currentData.columns();
        for (int i = 0; i < cols.size(); i++) {
            String value = i < row.size() ? row.get(i) : "";
            Label key = new Label(cols.get(i));
            key.getStyleClass().add("kv-key");
            key.setMinWidth(110);
            Label val = new Label(value);
            val.getStyleClass().add("kv-value");
            val.setWrapText(true);
            HBox line = new HBox(10, key, val);
            line.setAlignment(Pos.TOP_LEFT);
            overviewBox.getChildren().add(line);
        }

        // Build the tab set for this kind: Overview + YAML always; Events when applicable; Logs for pods.
        boolean isPod = currentKind == ResourceKind.PODS;
        detailsTabs.getTabs().setAll(overviewTab, yamlTab);
        if (kindHasEvents(currentKind)) {
            detailsTabs.getTabs().add(eventsTab);
        }
        if (isPod) {
            detailsTabs.getTabs().add(logsTab);
            detailsTabs.getTabs().add(shellTab);
        }
        // Any previously open shell belongs to the old selection — end it.
        closeExec();

        // Reveal the pane in the split (default ~40% width; user can drag it past half).
        if (!centerSplit.getItems().contains(detailsPane)) {
            centerSplit.getItems().add(detailsPane);
            SplitPane.setResizableWithParent(detailsPane, false);
            centerSplit.setDividerPositions(0.6);
        }
        // Don't try to select a tab that isn't present (e.g. Logs on a non-pod).
        detailsTabs.getSelectionModel().select(
                detailsTabs.getTabs().contains(target) ? target : overviewTab);

        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            yamlArea.setText("# no context selected");
            return;
        }

        // YAML: fetched lazily off the FX thread.
        yamlArea.setText("Loading…");
        runAsync(() -> service.describeYaml(ctx.name(), namespaceBox.getValue(), currentKind, name),
                yamlArea::setText);

        // Events: the "why" behind a failing resource (skipped for kinds that don't emit events).
        if (kindHasEvents(currentKind)) {
            eventsArea.setText("Loading…");
            runAsync(() -> service.describeEvents(ctx.name(), namespaceBox.getValue(), currentKind, name),
                    eventsArea::setText);
        }

        // Logs: load the container list, then the log tail.
        if (isPod) {
            currentLogPod = name;
            shellPod = name;
            shellArea.clear();
            logsArea.setText("Loading…");
            String ns = namespaceBox.getValue();
            runAsync(() -> service.listPodContainers(ctx.name(), ns, name), containers -> {
                // Populate with the handler detached so we trigger exactly one load below,
                // even when the first container name is unchanged from the previous pod.
                containerBox.setOnAction(null);
                containerBox.setItems(FXCollections.observableArrayList(containers));
                containerBox.setValue(containers.isEmpty() ? null : containers.get(0));
                containerBox.setOnAction(e -> loadLogs());
                loadLogs();
                // The shell shares the same container list.
                shellContainerBox.setItems(FXCollections.observableArrayList(containers));
                shellContainerBox.setValue(containers.isEmpty() ? null : containers.get(0));
            });
        } else {
            currentLogPod = null;
            shellPod = null;
        }
    }

    /** (Re)load the log tail for the currently shown pod + selected container. */
    private void loadLogs() {
        if (currentKind != ResourceKind.PODS || currentLogPod == null) {
            return;
        }
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = namespaceBox.getValue();
        String pod = currentLogPod;
        String container = containerBox.getValue();
        int tail = tailBox.getValue() != null ? tailBox.getValue() : 500;
        logsArea.setText("Loading logs…");
        runAsync(() -> service.podLogs(ctx.name(), ns, pod, container, tail), logs -> {
            logsArea.setText(logs);
            logsArea.positionCaret(logs.length());
            logsArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    /** Save the currently displayed log to a .log file the user can share with colleagues. */
    private void saveLogs() {
        String content = logsArea.getText();
        if (content == null || content.isBlank() || currentLogPod == null) {
            status("Nothing to save — open a pod's logs first.");
            return;
        }
        String container = containerBox.getValue();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String suggested = sanitizeFileName(currentLogPod
                + (container != null && !container.isBlank() ? "_" + container : "")
                + "_" + stamp + ".log");

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save pod logs");
        chooser.setInitialFileName(suggested);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Log file (*.log)", "*.log"),
                new FileChooser.ExtensionFilter("Text file (*.txt)", "*.txt"),
                new FileChooser.ExtensionFilter("All files", "*.*"));

        File target = chooser.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
        if (target == null) {
            return;
        }
        try {
            Files.writeString(target.toPath(), content);
            status("Saved logs to " + target.getAbsolutePath());
        } catch (Exception ex) {
            status("Could not save logs: " + ex.getMessage());
        }
    }

    /** Strip characters that are invalid in file names across Windows/macOS/Linux. */
    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "-");
    }

    private void closeDetails() {
        closeExec();
        centerSplit.getItems().remove(detailsPane);
    }

    // --- mutating actions -------------------------------------------------------------------

    private void deleteAction(List<String> row) {
        if (row == null) {
            return;
        }
        String name = resourceName(row);
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = namespaceBox.getValue();
        boolean ok = confirm("Delete " + currentKind.label().toLowerCase() + "?",
                "Delete \"" + name + "\""
                        + (currentKind.namespaced() ? " in namespace \"" + ns + "\"" : "") + "?\n\n"
                        + "This cannot be undone. (If it's managed by a controller, a replacement "
                        + "may be created automatically.)");
        if (!ok) {
            return;
        }
        status("Deleting " + name + "…");
        runAsyncVoid(() -> service.deleteResource(ctx.name(), ns, currentKind, name),
                () -> {
                    status("Deleted " + name + ".");
                    refreshContent();
                });
    }

    private void restartAction(List<String> row) {
        if (row == null) {
            return;
        }
        String name = resourceName(row);
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = namespaceBox.getValue();
        boolean ok = confirm("Restart deployment?",
                "Trigger a rolling restart of \"" + name + "\" in \"" + ns + "\"?\n\n"
                        + "Pods will be recreated gradually with no downtime if replicas > 1.");
        if (!ok) {
            return;
        }
        status("Restarting " + name + "…");
        runAsyncVoid(() -> service.restartDeployment(ctx.name(), ns, name),
                () -> status("Rolling restart triggered for " + name + "."));
    }

    private void scaleAction(List<String> row) {
        if (row == null) {
            return;
        }
        String name = resourceName(row);
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = namespaceBox.getValue();

        String current = currentReplicasFromRow(row);
        TextInputDialog dialog = new TextInputDialog(current != null ? current : "");
        dialog.setTitle("Scale deployment");
        dialog.setHeaderText("Scale \"" + name + "\""
                + (current != null ? "  (currently " + current + " replicas)" : ""));
        dialog.setContentText("Desired replicas:");
        Optional<String> answer = dialog.showAndWait();
        if (answer.isEmpty()) {
            return;
        }
        int replicas;
        try {
            replicas = Integer.parseInt(answer.get().trim());
            if (replicas < 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            status("Scale cancelled — \"" + answer.get() + "\" is not a valid replica count.");
            return;
        }
        status("Scaling " + name + " to " + replicas + "…");
        runAsyncVoid(() -> service.scaleDeployment(ctx.name(), ns, name, replicas),
                () -> status("Scaled " + name + " to " + replicas + " replicas."));
    }

    private void applyYamlAction() {
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null || currentDetailName == null) {
            return;
        }
        String ns = namespaceBox.getValue();
        boolean ok = confirm("Apply changes?",
                "Apply your edits to \"" + currentDetailName + "\"?\n\n"
                        + "This replaces the live resource with the YAML shown here.");
        if (!ok) {
            return;
        }
        String yaml = yamlArea.getText();
        status("Applying changes to " + currentDetailName + "…");
        runAsyncVoid(() -> service.applyYaml(ctx.name(), ns, yaml),
                () -> {
                    status("Applied changes to " + currentDetailName + ".");
                    refreshContent();
                });
    }

    /** Pull the desired replica count from a deployment row's "Ready" cell (formatted "ready/desired"). */
    private String currentReplicasFromRow(List<String> row) {
        int idx = currentData.columns().indexOf("Ready");
        if (idx >= 0 && idx < row.size()) {
            String ready = row.get(idx);
            int slash = ready.indexOf('/');
            if (slash >= 0) {
                return ready.substring(slash + 1).trim();
            }
        }
        return null;
    }

    /** The global "+ Create" action: a blank editor to paste/open manifests and apply them. */
    private void openCreateDialog() {
        if (contextBox.getValue() == null) {
            status("Select a context first.");
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Create / Apply YAML");
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        ButtonType applyType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CANCEL);

        TextArea editor = new TextArea();
        editor.setPromptText("Paste or write Kubernetes manifests here.\n"
                + "Multiple documents separated by --- are supported.");
        editor.getStyleClass().add("yaml-area");
        editor.setPrefColumnCount(90);
        editor.setPrefRowCount(26);
        VBox.setVgrow(editor, Priority.ALWAYS);

        Button openFile = new Button("Open file…");
        openFile.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Open manifest");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("YAML (*.yaml, *.yml)", "*.yaml", "*.yml"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            File f = fc.showOpenDialog(dialog.getOwner());
            if (f != null) {
                try {
                    editor.setText(Files.readString(f.toPath()));
                } catch (Exception ex) {
                    editor.setText("# could not read file: " + ex.getMessage());
                }
            }
        });

        String ns = namespaceBox.getValue();
        Label hint = new Label("Server-side apply · resources without a namespace go to: "
                + (ns != null ? ns : "(context default)"));
        hint.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, openFile, spacer, hint);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, top, editor);
        content.setPrefSize(780, 560);
        dialog.getDialogPane().setContent(content);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == applyType) {
            applyManifestText(editor.getText());
        }
    }

    private void applyManifestText(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            status("Nothing to apply.");
            return;
        }
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = namespaceBox.getValue();
        status("Applying manifests…");
        runAsync(() -> service.applyManifests(ctx.name(), ns, yaml), summary -> {
            showReport("Apply result", summary);
            status("Apply complete.");
            refreshContent();
        });
    }

    /** Show a multi-line, read-only report (e.g. the result of applying manifests). */
    private void showReport(String header, String text) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("KubeDesk");
        alert.setHeaderText(header);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefSize(540, 280);
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

    /** Confirm dialog returning true if the user accepted. */
    private boolean confirm(String header, String body) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, body, ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle("KubeDesk");
        alert.setHeaderText(header);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private void copyYaml(List<String> row) {
        if (row == null) {
            return;
        }
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String name = resourceName(row);
        status("Copying YAML for " + name + "…");
        runAsync(() -> service.describeYaml(ctx.name(), namespaceBox.getValue(), currentKind, name),
                yaml -> {
                    copyToClipboard(yaml);
                    status("Copied YAML for " + name + " to clipboard.");
                });
    }

    private void copyToClipboard(String text) {
        if (text == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** ConfigMaps and Secrets don't emit events, so the Events action is noise for them. */
    private boolean kindHasEvents(ResourceKind kind) {
        return kind != ResourceKind.CONFIGMAPS && kind != ResourceKind.SECRETS;
    }

    /** The resource name is always column 0 of our table data. */
    private String resourceName(List<String> row) {
        return row != null && !row.isEmpty() ? row.get(0) : "";
    }

    private String safeNamespace() {
        String ns = namespaceBox.getValue();
        return ns == null ? "" : ns;
    }

    // --- data loading -----------------------------------------------------------------------

    private void onContextChosen() {
        ContextInfo c = contextBox.getValue();
        if (c != null) {
            loadNamespaces(c);
        }
    }

    private void loadContexts() {
        status("Reading kube.conf…");
        runAsync(service::listContexts, contexts -> {
            ContextInfo current = contexts.stream().filter(ContextInfo::current).findFirst()
                    .orElse(contexts.isEmpty() ? null : contexts.get(0));
            // Set the value with the handler detached so we drive the load exactly once below.
            contextBox.setOnAction(null);
            contextBox.setItems(FXCollections.observableArrayList(contexts));
            contextBox.setValue(current);
            contextBox.setOnAction(e -> onContextChosen());
            if (current != null) {
                loadNamespaces(current);
            } else {
                status("No contexts found in kube.conf.");
            }
        });
    }

    private void loadNamespaces(ContextInfo context) {
        status("Loading namespaces for " + context.name() + "…");
        runAsync(() -> service.listNamespaces(context.name()), namespaces -> {
            String chosen;
            if (namespaces.contains(context.namespace())) {
                chosen = context.namespace();
            } else if (namespaces.contains("default")) {
                chosen = "default";
            } else {
                chosen = namespaces.isEmpty() ? null : namespaces.get(0);
            }
            // Populate with the handler detached, then trigger a single reload + watch.
            namespaceBox.setOnAction(null);
            namespaceBox.setItems(FXCollections.observableArrayList(namespaces));
            namespaceBox.setValue(chosen);
            namespaceBox.setOnAction(e -> onSelectionChanged());
            onSelectionChanged();
        });
    }

    private void refreshContent() {
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = namespaceBox.getValue();
        if (currentKind.namespaced() && ns == null) {
            return;
        }
        status("Loading " + currentKind.label() + "…");
        runAsync(() -> service.listResource(ctx.name(), ns, currentKind), data -> {
            currentData = data;
            renderTable(data);
            applyFilter();
            status(data.rows().size() + " " + currentKind.label().toLowerCase() + " loaded.");
        });

        if (currentKind == ResourceKind.PODS) {
            runAsync(() -> service.podHealth(ctx.name(), ns), this::renderHealthRing);
        } else {
            healthRing.getChildren().clear();
        }
    }

    // --- live updates (watches) -------------------------------------------------------------

    /** Called when the user changes context/namespace/kind: reload data and (re)start the watch. */
    private void onSelectionChanged() {
        refreshContent();
        if (paused) {
            updateLiveUi();
        } else {
            restartWatch();
        }
    }

    /** Toggle live updates on/off without losing your place. */
    private void togglePause() {
        paused = !paused;
        if (paused) {
            stopWatch();
        } else {
            restartWatch();
            refreshContent(); // catch up on anything that changed while paused
        }
        updateLiveUi();
    }

    /** Stop any existing watch and start a fresh one for the current selection. */
    private void restartWatch() {
        stopWatch();
        if (paused) {
            return;
        }
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = namespaceBox.getValue();
        if (currentKind.namespaced() && ns == null) {
            return;
        }
        try {
            currentWatch = service.watchResource(ctx.name(), ns, currentKind,
                    this::scheduleAutoRefresh, this::onWatchClosed);
        } catch (Exception e) {
            status("Live updates unavailable: " + e.getMessage());
        }
        updateLiveUi();
    }

    private void stopWatch() {
        if (currentWatch != null) {
            try {
                currentWatch.close();
            } catch (Exception ignored) {
                // best-effort close
            }
            currentWatch = null;
        }
        updateLiveUi();
    }

    /** Watch event arrived (background thread) — debounce a re-list onto the FX thread. */
    private void scheduleAutoRefresh() {
        Platform.runLater(autoRefreshPause::playFromStart);
    }

    /** A watch dropped unexpectedly — reconnect for the still-current selection. */
    private void onWatchClosed(Exception cause) {
        Platform.runLater(() -> {
            if (paused) {
                return;
            }
            status("Live updates dropped, reconnecting…");
            restartWatch();
        });
    }

    /** Reflect the current live/paused state in the top-bar indicator + toggle button. */
    private void updateLiveUi() {
        // Show the indicator whenever updates are live or explicitly paused.
        boolean show = currentWatch != null || paused;
        liveBox.setVisible(show);
        liveBox.setManaged(show);

        if (paused) {
            liveLabel.setText("❚❚ Paused");
            liveLabel.getStyleClass().setAll("live-indicator", "paused");
            pauseButton.setText("▶");
            pauseButton.getStyleClass().setAll("live-toggle");
            pauseButton.setTooltip(new Tooltip("Resume live updates"));
        } else {
            liveLabel.setText("● Live");
            liveLabel.getStyleClass().setAll("live-indicator");
            pauseButton.setText("⏸");
            pauseButton.getStyleClass().setAll("live-toggle");
            pauseButton.setTooltip(new Tooltip("Pause live updates"));
        }
    }

    /** Release the watch and worker threads on shutdown so the JVM can exit cleanly. */
    public void shutdown() {
        closeExec();
        stopWatch();
        pool.shutdownNow();
    }

    // --- rendering --------------------------------------------------------------------------

    private void renderTable(ResourceData data) {
        table.getColumns().clear();
        Set<String> hidden = hiddenColumns.computeIfAbsent(currentKind, k -> new HashSet<>());
        List<String> cols = data.columns();
        for (int i = 0; i < cols.size(); i++) {
            final int idx = i;
            String name = cols.get(i);
            TableColumn<List<String>, String> col = new TableColumn<>(name);
            col.setCellValueFactory(cell -> {
                List<String> row = cell.getValue();
                String v = idx < row.size() ? row.get(idx) : "";
                return new javafx.beans.property.SimpleStringProperty(v);
            });
            // Per-column sizing so "Name" gets room while "Age"/"Restarts" stay tight.
            col.setPrefWidth(columnPrefWidth(name));
            col.setMinWidth(columnMinWidth(name));
            col.setMaxWidth(columnMaxWidth(name));
            if (isCenteredColumn(name)) {
                col.setStyle("-fx-alignment: CENTER;");
            }
            if (isStatusColumn(name)) {
                col.setCellFactory(c -> statusChipCell());
            }
            // Restore the user's show/hide choice and keep tracking it for this kind.
            col.setVisible(!hidden.contains(name));
            col.visibleProperty().addListener((obs, was, isVisible) -> {
                if (isVisible) {
                    hidden.remove(name);
                } else {
                    hidden.add(name);
                }
            });
            table.getColumns().add(col);
        }
        table.setItems(FXCollections.observableArrayList(data.rows()));
    }

    private boolean isStatusColumn(String name) {
        return name.equalsIgnoreCase("Status");
    }

    /** Preferred starting width per column; the constrained policy grows these to fill the table. */
    private double columnPrefWidth(String name) {
        return switch (name) {
            case "Image" -> 260;
            case "Name" -> 220;
            case "Node", "Hosts", "Volume" -> 200;
            case "Ports" -> 170;
            case "Address", "Schedule" -> 150;
            case "Roles", "Version", "Cluster-IP", "StorageClass", "Last Schedule", "Access Modes" -> 140;
            case "Status", "Type", "Class" -> 120;
            case "Up-to-date", "Completions" -> 100;
            case "Available", "Restarts", "Ready", "Desired", "Active", "Capacity" -> 90;
            case "Suspend", "Data" -> 80;
            case "Age" -> 70;
            default -> 130;
        };
    }

    /** Floor width so narrow columns never collapse and "Name" never gets crushed. */
    private double columnMinWidth(String name) {
        return switch (name) {
            case "Name" -> 160;
            case "Image" -> 140;
            case "Node", "Ports", "Cluster-IP", "Roles", "Version", "Hosts", "Address" -> 100;
            case "Age" -> 56;
            default -> 70;
        };
    }

    /**
     * Hard ceiling per column. "Name" is capped so it can't balloon to fill the table; the leftover
     * space flows to free-text columns (Node, Ports, Roles) which have no cap.
     */
    private double columnMaxWidth(String name) {
        return switch (name) {
            case "Name" -> 260;
            case "Status", "Type" -> 140;
            case "Ready", "Up-to-date", "Available", "Restarts" -> 110;
            case "Age" -> 90;
            default -> Double.MAX_VALUE;
        };
    }

    /** Short, fixed-format columns read better centered; free-text columns stay left-aligned. */
    private boolean isCenteredColumn(String name) {
        return switch (name) {
            case "Ready", "Up-to-date", "Available", "Restarts", "Age", "Type", "Version",
                 "Desired", "Completions", "Active", "Suspend", "Data", "Capacity", "Last Schedule" -> true;
            default -> false;
        };
    }

    /** A table cell that draws the value as a coloured status chip. */
    private TableCell<List<String>, String> statusChipCell() {
        return new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("chip", "chip-ok", "chip-warn", "chip-bad", "chip-neutral");
                if (empty || value == null) {
                    setText(null);
                } else {
                    setText(value);
                    getStyleClass().add("chip");
                    getStyleClass().add(chipClassFor(value));
                }
            }
        };
    }

    private String chipClassFor(String value) {
        String v = value.toLowerCase();
        if (v.contains("running") || v.contains("ready") || v.contains("active") || v.contains("succeeded")
                || v.contains("complete") || v.contains("bound")) {
            return "chip-ok";
        }
        if (v.contains("pending") || v.contains("containercreating") || v.contains("terminating")) {
            return "chip-warn";
        }
        if (v.contains("fail") || v.contains("error") || v.contains("crash") || v.contains("notready")
                || v.contains("backoff") || v.contains("unknown") || v.contains("oom")
                || v.contains("evict") || v.contains("imagepull") || v.contains("errimage")
                || v.contains("lost")) {
            return "chip-bad";
        }
        return "chip-neutral";
    }

    private void renderHealthRing(HealthSummary summary) {
        healthRing.getChildren().clear();
        if (summary.total() == 0) {
            healthRing.getChildren().add(new Label("No pods in this namespace."));
            return;
        }
        PieChart chart = new PieChart();
        chart.setLegendVisible(false);
        chart.setLabelsVisible(false);
        chart.setPrefSize(140, 140);
        chart.setMaxSize(140, 140);
        chart.setStartAngle(90);

        summary.phaseCounts().forEach((phase, count) ->
                chart.getData().add(new PieChart.Data(phase, count)));

        // Colour each slice by phase health.
        chart.getData().forEach(d ->
                d.getNode().setStyle("-fx-pie-color: " + ringColorFor(d.getName()) + ";"));

        // Donut hole + total count in the middle.
        Circle hole = new Circle(34);
        hole.getStyleClass().add("ring-hole");
        Label total = new Label(String.valueOf(summary.total()));
        total.getStyleClass().add("ring-total");
        Label caption = new Label("pods");
        caption.getStyleClass().add("ring-caption");
        VBox center = new VBox(total, caption);
        center.setAlignment(Pos.CENTER);
        StackPane ring = new StackPane(chart, hole, center);

        VBox legend = new VBox(4);
        legend.setAlignment(Pos.CENTER_LEFT);
        summary.phaseCounts().forEach((phase, count) -> {
            Circle dot = new Circle(5);
            dot.setStyle("-fx-fill: " + ringColorFor(phase) + ";");
            Label l = new Label(phase + ": " + count);
            HBox row = new HBox(8, dot, l);
            row.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(row);
        });

        HBox card = new HBox(20, ring, legend);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(12, 20, 12, 12));
        healthRing.getChildren().add(card);
    }

    private String ringColorFor(String phase) {
        String p = phase.toLowerCase();
        if (p.contains("running") || p.contains("succeeded")) {
            return "#16a34a"; // green
        }
        if (p.contains("pending")) {
            return "#f59e0b"; // amber
        }
        if (p.contains("fail") || p.contains("unknown")) {
            return "#dc2626"; // red
        }
        return "#9ca3af"; // grey
    }

    /** Client-side filter across all columns of the current data. */
    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        if (q.isEmpty()) {
            table.setItems(FXCollections.observableArrayList(currentData.rows()));
            return;
        }
        List<List<String>> filtered = currentData.rows().stream()
                .filter(row -> row.stream().anyMatch(c -> c != null && c.toLowerCase().contains(q)))
                .toList();
        table.setItems(FXCollections.observableArrayList(filtered));
    }

    // --- async plumbing ---------------------------------------------------------------------

    /** Run a blocking service call off the FX thread and deliver the result back on it. */
    private <T> void runAsync(Supplier<T> work, Consumer<T> onSuccess) {
        Task<T> task = new Task<>() {
            @Override protected T call() {
                return work.get();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            status("Error: " + (ex != null ? ex.getMessage() : "unknown"));
        });
        pool.submit(task);
    }

    /** Like {@link #runAsync} but for a mutation that returns nothing. */
    private void runAsyncVoid(Runnable work, Runnable onSuccess) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                work.run();
                return null;
            }
        };
        task.setOnSucceeded(e -> onSuccess.run());
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            status("Error: " + (ex != null ? ex.getMessage() : "unknown"));
        });
        pool.submit(task);
    }

    private void status(String message) {
        if (Platform.isFxApplicationThread()) {
            statusBar.setText(message);
        } else {
            Platform.runLater(() -> statusBar.setText(message));
        }
    }

    private static ThreadFactory daemonThreads() {
        return r -> {
            Thread t = new Thread(r, "kubedesk-worker");
            t.setDaemon(true);
            return t;
        };
    }

    /** Thin vertical separator used in the top bar. */
    private static class VSep extends Region {
        VSep() {
            setMinWidth(1);
            setPrefWidth(1);
            setMaxHeight(22);
            getStyleClass().add("vsep");
        }
    }
}
