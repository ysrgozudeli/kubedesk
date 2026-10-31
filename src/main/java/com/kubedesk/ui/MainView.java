package com.kubedesk.ui;

import com.kubedesk.service.ClusterService;
import com.kubedesk.service.ExecSession;
import com.kubedesk.service.PortForward;
import com.kubedesk.service.Dtos.ClusterCapacity;
import com.kubedesk.service.Dtos.ContextInfo;
import com.kubedesk.service.Dtos.MetricSample;
import com.kubedesk.service.Dtos.Permissions;
import com.kubedesk.service.Dtos.HealthFinding;
import com.kubedesk.service.Dtos.HealthSeverity;
import com.kubedesk.service.Dtos.TopoNode;
import com.kubedesk.prefs.Settings;
import com.kubedesk.prefs.SettingsStore;
import com.kubedesk.mcp.KubeDeskMcpServer;
import com.kubedesk.mcp.McpActivity;
import com.kubedesk.mcp.McpServerPanel;
import com.kubedesk.update.UpdateService;
import com.kubedesk.util.YamlLint;
import com.kubedesk.service.Dtos.HealthSummary;
import com.kubedesk.service.Dtos.ResourceData;
import com.kubedesk.service.ResourceKind;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.util.Duration;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Modality;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The whole KubeDesk UI: a Kubernetes-Dashboard-style shell with a top bar (kube.conf picker +
 * context/namespace selectors), a sidebar of resource kinds, a pod-health ring, and a dynamic table.
 */
public class MainView extends BorderPane {

    private final ClusterService service;

    // Persisted preferences
    private final SettingsStore settingsStore = new SettingsStore();
    private final Settings settings = settingsStore.load();
    private boolean forwardsRestored = false;

    // Top bar controls
    private final Label kubeConfigLabel = new Label("(default ~/.kube/config)");
    private final ComboBox<ContextInfo> contextBox = new ComboBox<>();
    private final ComboBox<String> namespaceBox = new ComboBox<>();
    // Left-edge quick-switch rail: one colored avatar per context (cluster).
    private final VBox clusterRail = new VBox(8);
    private Button railToggle;
    private VBox railBody;
    private VBox railWrap;
    private Button loadConfigBtn;            // top-bar "Load kube.conf…" — shown only in the empty state
    private final Region clusterAccent = new Region(); // thin top strip in the active cluster's color
    // Clusters aggregated across every loaded kube.conf file (one active at a time). clusterFiles maps a
    // context name to its source file (null value = default discovery); activeConfigFile is the current one.
    private final List<ContextInfo> allClusters = new ArrayList<>();
    private final Map<String, File> clusterFiles = new HashMap<>();
    private File activeConfigFile;
    private final TextField searchField = new TextField();

    // Content. The center is a vertical split: the resource area on top, the kubectl console drawer
    // (added only when toggled on) below it, so the drawer is resizable and never steals space until used.
    private final SplitPane verticalSplit = new SplitPane();
    private final SplitPane centerSplit = new SplitPane();
    private KubectlConsole console; // built lazily the first time the drawer is opened
    private CommandPalette palette; // Ctrl/Cmd+P quick-switcher, built lazily on first use
    private final CheckMenuItem consoleMenuItem = new CheckMenuItem("kubectl console");
    private final TableView<List<String>> table = new TableView<>();
    private final HBox healthRing = new HBox();
    private final Label contentTitle = new Label("Pods");
    // Read-only TextField (not a Label) so the status message is selectable and copyable.
    private final TextField statusBar = new TextField("Ready.");
    private static final PseudoClass ERROR_PSEUDO = PseudoClass.getPseudoClass("error");
    // Auto-dismiss a lingering error from the status bar after a while (any new status cancels it).
    private final PauseTransition statusErrorClear = new PauseTransition(Duration.seconds(15));

    // Extra views (topology, health) live in the same center area as the resource table.
    private enum ViewMode { RESOURCE, TOPOLOGY, HEALTH, EVENTS, OVERVIEW }

    private ViewMode currentView = ViewMode.RESOURCE;
    private final Map<ResourceKind, ToggleButton> navButtons = new EnumMap<>(ResourceKind.class);

    private final TopologyView topologyView = new TopologyView();
    private final ToggleButton topologyToggle = new ToggleButton("›  Topology");

    private final ToggleButton healthToggle = new ToggleButton("›  Health");
    private final VBox healthView = new VBox(10);
    private final TableView<HealthFinding> healthTable = new TableView<>();
    private final Label healthSummary = new Label();

    private final ToggleButton eventsToggle = new ToggleButton("›  Events");
    private final VBox eventsView = new VBox(10);
    private final TableView<List<String>> nsEventsTable = new TableView<>();
    private final Label eventsSummary = new Label();

    // Overview dashboard: stat tiles + unhealthy pods + warning events for the selected namespace.
    private final ToggleButton overviewToggle = new ToggleButton("›  Overview");
    private final VBox overviewView = new VBox(14);
    private final StatTile tilePods = new StatTile("Pods", "◉", "badge-pods");
    private final StatTile tileWarnings = new StatTile("Warnings", "⚠", "badge-warnings");
    private final StatTile tileDeployments = new StatTile("Deployments", "⬢", "badge-deployments");
    private final StatTile tileNodes = new StatTile("Nodes", "▤", "badge-nodes");
    private final StatTile tileNamespaces = new StatTile("Namespaces", "▦", "badge-namespaces");
    // Cluster-wide capacity meters (requested/used vs allocatable) and a per-node usage table.
    private final VBox overviewCapacityBox = new VBox(12);
    private final VBox overviewNodesBox = new VBox(4);
    private final VBox overviewUnhealthyBox = new VBox(4);
    private final VBox overviewEventsBox = new VBox(4);
    // Overview header: cluster identity (color dot, name, environment chip, config file).
    private final Label ovDot = new Label("●");
    private final Label ovName = new Label();
    private final Label ovEnvChip = new Label();
    private final Label ovMeta = new Label();
    private int overviewGen;

    // Details panel (right side)
    private final VBox detailsPane = new VBox();
    private final Label detailsTitle = new Label();
    private final Label detailsSubtitle = new Label();
    private final MenuButton detailsActionsBtn = new MenuButton("Actions");
    private final VBox overviewBox = new VBox(6);
    private final TextArea yamlArea = new TextArea();
    private final TabPane detailsTabs = new TabPane();
    private final TextArea eventsArea = new TextArea();
    private final Tab overviewTab = new Tab("Overview", overviewBox);
    private final Tab yamlTab = new Tab("YAML", yamlArea);
    private final Tab eventsTab = new Tab("Events", eventsArea);
    private final Tab logsTab = new Tab("Logs");
    private final Tab shellTab = new Tab("Shell");

    // Live metrics (pods): polls the Metrics API and accumulates a client-side time-series.
    private final Tab metricsTab = new Tab("Metrics");
    private final XYChart.Series<Number, Number> cpuSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> memSeries = new XYChart.Series<>();
    private Timeline metricsTimeline;
    private long metricsStartMillis;
    private String metricsPod;
    private String metricsNs;

    // Logs view controls
    private final ComboBox<String> containerBox = new ComboBox<>();
    private final ComboBox<Integer> tailBox = new ComboBox<>();
    private final TextArea logsArea = new TextArea();
    private final TailScroller logTail = new TailScroller(logsArea);
    private final LogBuffer logBuffer = new LogBuffer(logsArea, logTail);
    private final Label logStatusDot = new Label("●");
    private String currentLogPod;
    private String currentLogPodStatus;
    // Live log follow (kubectl logs -f): a toggle plus the handle to the running stream, if any.
    private final ToggleButton followToggle = new ToggleButton("Follow");
    private final CheckBox previousCheck = new CheckBox("Previous");
    private final CheckBox timestampsCheck = new CheckBox("Timestamps");
    private AutoCloseable logStream;

    // Shell (exec) controls
    private final ComboBox<String> shellContainerBox = new ComboBox<>();
    private final TextArea shellArea = new TextArea();
    private final TextField shellInput = new TextField();
    private final Button shellConnectBtn = new Button("Connect");
    private ExecSession execSession;
    private String shellPod;
    /** Name + kind of the resource currently shown in the details panel (for YAML reload/logs). */
    private String currentDetailName;
    private ResourceKind detailKind = ResourceKind.PODS;

    private ResourceKind currentKind = ResourceKind.PODS;
    /** A resource name to select + open once the next resource-table load finishes (deep-link from Overview). */
    private String pendingSelectName;

    // "Search all listed namespaces" mode: a sentinel entry in the namespace dropdown. In this mode
    // the table gains a leading Namespace column, is read-only (view YAML only), and has no live watch.
    private static final String ALL_NAMESPACES = "✿ All namespaces";
    private final BooleanProperty allNsMode = new SimpleBooleanProperty(false);

    // Proactive RBAC awareness: what the user can do in the current namespace (fail-open by default).
    private Permissions currentPermissions = Permissions.unknown();
    private String permKey; // context|namespace the current permissions were fetched for
    private final Map<String, Label> sectionLabels = new HashMap<>();
    private Button createBtn;
    private ResourceData currentData = new ResourceData(List.of(), List.of());

    /** Per-kind set of column names the user has hidden via the table's column menu. */
    private final Map<ResourceKind, Set<String>> hiddenColumns = new EnumMap<>(ResourceKind.class);

    // Port-forwards (global, long-lived)
    private final List<PortForward> portForwards = new ArrayList<>();
    private final Button pfButton = new Button("Port-forwards");

    // Auto-update: checks GitHub on startup and offers a one-click installer download.
    private final UpdateService updateService = new UpdateService();
    private final HBox updateBanner = new HBox(12);

    // Embedded MCP server (experimental, read-only). Built lazily; reads the live context/namespace via
    // suppliers so a tool call always targets what's selected now.
    private KubeDeskMcpServer mcpServer;

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

    public MainView(ClusterService service) {
        this.service = service;
        getStyleClass().add("root-pane");
        restoreHiddenColumns();
        applyColumnDefaults();
        buildDetails(); // builds detailsPane (added to the split only when shown)
        centerSplit.getItems().setAll(buildContent());
        // Let the center collapse fully so the bottom status bar is never pushed off-screen when the
        // window is short — the table area shrinks instead of the status bar disappearing.
        centerSplit.setMinHeight(0);
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.setMinHeight(0);
        verticalSplit.getItems().setAll(centerSplit);
        setLeft(new HBox(buildClusterRail(), buildSidebar()));
        // The controls toolbar sits above the content area (not a full-width top bar).
        VBox centerPane = new VBox(buildToolbar(), verticalSplit);
        VBox.setVgrow(verticalSplit, Priority.ALWAYS);
        centerPane.setMinHeight(0);
        setCenter(centerPane);
        setBottom(buildStatusBar());

        // Toolbar with the update banner stacked just under it (hidden until an update is found).
        configureUpdateBanner();
        // A thin strip at the very top, tinted with the active cluster's color (environment-aware) —
        // an always-visible "which cluster am I on" cue (red = PROD, etc.).
        clusterAccent.setPrefHeight(4);
        clusterAccent.setMinHeight(4);
        clusterAccent.setMaxHeight(4);
        clusterAccent.setStyle("-fx-background-color: transparent;");
        setTop(new VBox(clusterAccent, updateBanner));

        // Ctrl/Cmd+K toggles the kubectl console. Registered on the scene (not the menu item) because a
        // MenuButton item's accelerator only fires while its popup is open — useless as a global shortcut.
        sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                scene.getAccelerators().put(
                        new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN),
                        () -> toggleConsole(!consoleMenuItem.isSelected()));
                // Ctrl/Cmd+P — quick-switcher for contexts, namespaces, resources and views.
                scene.getAccelerators().put(
                        new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN),
                        this::openPalette);
            }
        });
    }

    /** Kick off the first load once the scene is shown. */
    public void init() {
        // When a burst of watch events settles, re-list the current view once.
        autoRefreshPause.setOnFinished(e -> refreshContent());
        // Clear a stale error back to "Ready." once its pause elapses (no newer status arrived).
        statusErrorClear.setOnFinished(e -> {
            statusBar.setText("Ready.");
            statusBar.pseudoClassStateChanged(ERROR_PSEUDO, false);
        });
        applySavedKubeConfig();
        loadContexts();
        reloadClusters();
        checkForUpdates(false);
        maybeStartMcpServer();
    }

    /** If the user left the (experimental) MCP server enabled last session, start it again on launch. */
    private void maybeStartMcpServer() {
        if (!settings.mcpEnabled) {
            return;
        }
        try {
            mcpServer().start(settings.mcpPort);
            status("MCP server (experimental) listening on " + mcpServer().endpoint()
                    + " — dev/testing use only.");
        } catch (Exception e) {
            status("Couldn't start the MCP server on port " + settings.mcpPort + ": " + e.getMessage());
        }
    }

    // --- auto-update ------------------------------------------------------------------------

    private void configureUpdateBanner() {
        updateBanner.getStyleClass().add("update-banner");
        updateBanner.setAlignment(Pos.CENTER_LEFT);
        updateBanner.setPadding(new Insets(8, 16, 8, 16));
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);
    }

    /**
     * Ask GitHub (async) whether a newer release exists; show the banner if so. When
     * {@code userInitiated} is true (the "Check for updates" menu), give feedback either way and
     * ignore a previously-skipped version; the silent startup check does neither.
     */
    private void checkForUpdates(boolean userInitiated) {
        if (!userInitiated && !settings.updateCheckEnabled) {
            return;
        }
        if (userInitiated) {
            status("Checking for updates…");
        }
        pool.submit(() -> {
            Optional<UpdateService.ReleaseInfo> latest = updateService.latestRelease();
            Platform.runLater(() -> {
                if (latest.isEmpty()) {
                    if (userInitiated) {
                        statusError("Couldn't check for updates (offline or rate-limited).");
                    }
                    return;
                }
                UpdateService.ReleaseInfo r = latest.get();
                boolean newer = UpdateService.isNewer(r.version(), UpdateService.currentVersion());
                if (newer && (userInitiated || !r.version().equals(settings.skippedVersion))) {
                    showUpdateBanner(r);
                } else if (userInitiated) {
                    status("You're on the latest version (" + UpdateService.currentVersion() + ").");
                }
            });
        });
    }

    private void showAboutDialog() {
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("About KubeDesk");
        dlg.setHeaderText("KubeDesk " + UpdateService.currentVersion());

        try (var in = getClass().getResourceAsStream("/icons/kubedesk.png")) {
            if (in != null) {
                ImageView icon = new ImageView(new Image(in));
                icon.setFitWidth(48);
                icon.setFitHeight(48);
                dlg.setGraphic(icon);
            }
        } catch (Exception ignore) {
            // no icon — not important
        }

        Label desc = new Label("A lightweight, cross-platform desktop client for managing "
                + "Kubernetes clusters — talks directly to the API server, no kubectl required.");
        desc.setWrapText(true);
        desc.setMaxWidth(360);

        Hyperlink repo = new Hyperlink("github.com/ysrgozudeli/kubedesk");
        repo.setOnAction(e -> openInBrowser("https://github.com/ysrgozudeli/kubedesk"));

        Label license = new Label("MIT License © Yasar Gozudeli");
        license.getStyleClass().add("muted");

        VBox box = new VBox(8, desc, repo, license);
        box.setPadding(new Insets(12, 4, 4, 4));
        dlg.getDialogPane().setContent(box);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        if (getScene() != null) {
            dlg.initOwner(getScene().getWindow());
        }
        dlg.showAndWait();
    }

    private void showUpdateBanner(UpdateService.ReleaseInfo release) {
        Label label = new Label("KubeDesk " + release.version() + " is available.");
        label.getStyleClass().add("update-banner-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button notes = new Button("What's new");
        notes.setOnAction(e -> openInBrowser(release.htmlUrl()));

        Button update = new Button("Update now");
        update.getStyleClass().add("accent-button");
        update.setOnAction(e -> updateNow(release));

        Button dismiss = new Button("Dismiss");
        dismiss.setTooltip(new Tooltip("Don't show this version again"));
        dismiss.setOnAction(e -> {
            settings.skippedVersion = release.version();
            saveSettings();
            hideUpdateBanner();
        });

        updateBanner.getChildren().setAll(new Label("⬆"), label, spacer, notes, update, dismiss);
        updateBanner.setVisible(true);
        updateBanner.setManaged(true);
    }

    private void hideUpdateBanner() {
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);
    }

    /** Download the OS-appropriate installer and, once done, offer to install it. */
    private void updateNow(UpdateService.ReleaseInfo release) {
        Optional<UpdateService.Asset> asset = updateService.installerForCurrentOs(release);
        if (asset.isEmpty()) {
            // No installer for this OS/build (e.g. a portable copy) — just open the download page.
            openInBrowser(release.htmlUrl());
            return;
        }
        UpdateService.Asset a = asset.get();

        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(340);
        Label msg = new Label("Downloading " + a.name() + " …");
        VBox box = new VBox(10, msg, bar);
        box.setPadding(new Insets(16));

        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("KubeDesk update");
        dlg.setHeaderText("Getting KubeDesk " + release.version());
        dlg.getDialogPane().setContent(box);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        if (getScene() != null) {
            dlg.initOwner(getScene().getWindow());
        }
        dlg.initModality(Modality.APPLICATION_MODAL);

        final Future<?>[] holder = new Future<?>[1];
        // Closing the dialog (Cancel) aborts the download.
        dlg.setOnHidden(e -> {
            if (holder[0] != null) {
                holder[0].cancel(true);
            }
        });

        holder[0] = pool.submit(() -> {
            try {
                Path file = updateService.download(a, frac -> Platform.runLater(() ->
                        bar.setProgress(frac < 0 ? ProgressBar.INDETERMINATE_PROGRESS : frac)));
                Platform.runLater(() -> {
                    dlg.setResult(null);
                    dlg.close();
                    // Deferred so this runs after the dialog has fully closed (no nested modal loop).
                    Platform.runLater(() -> offerToInstall(release, file));
                });
            } catch (InterruptedException cancelled) {
                Platform.runLater(() -> status("Update download cancelled."));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    dlg.setResult(null);
                    dlg.close();
                    statusError("Update download failed: " + ex.getMessage());
                });
            }
        });

        // show() (not showAndWait) — modal but non-blocking, so completing the download and then
        // opening the install confirmation doesn't nest two event loops and freeze the UI.
        dlg.show();
    }

    /** Confirm, then launch the installer and quit so it can replace the running app. */
    private void offerToInstall(UpdateService.ReleaseInfo release, Path installer) {
        boolean ok = confirm("Install KubeDesk " + release.version() + "?",
                "The installer was downloaded to:\n" + installer + "\n\n"
                        + "KubeDesk will close so the installer can replace it. "
                        + "(It is not code-signed, so Windows may show an \"unknown publisher\" prompt "
                        + "— that's expected; choose Run/Yes.)");
        if (!ok) {
            status("Update saved to " + installer + " — run it when you're ready.");
            return;
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String name = installer.getFileName().toString().toLowerCase();
            if (os.contains("win") && name.endsWith(".msi")) {
                new ProcessBuilder("msiexec", "/i", installer.toString()).start();
            } else if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(installer.toFile());
            } else {
                new ProcessBuilder(os.contains("mac") ? "open" : "xdg-open",
                        installer.toString()).start();
            }
            // Quit so the installer isn't blocked by files in use.
            shutdown();
            Platform.exit();
            System.exit(0);
        } catch (Exception e) {
            statusError("Couldn't launch the installer: " + e.getMessage());
        }
    }

    // --- preferences ------------------------------------------------------------------------

    private void restoreHiddenColumns() {
        settings.hiddenColumns.forEach((kindName, cols) -> {
            try {
                hiddenColumns.put(ResourceKind.valueOf(kindName), new HashSet<>(cols));
            } catch (IllegalArgumentException ignore) {
                // unknown kind name in old prefs — skip
            }
        });
    }

    /** Seed first-run defaults once: hide the more verbose pod columns (user can re-enable them). */
    private void applyColumnDefaults() {
        if (settings.columnDefaultsApplied) {
            return;
        }
        hiddenColumns.computeIfAbsent(ResourceKind.PODS, k -> new HashSet<>())
                .addAll(List.of("IP", "QoS", "Controlled By"));
        settings.columnDefaultsApplied = true;
        persistHiddenColumns();
    }

    private void applySavedKubeConfig() {
        if (settings.kubeConfigPath != null && !settings.kubeConfigPath.isBlank()) {
            File f = new File(settings.kubeConfigPath);
            if (f.exists()) {
                service.setKubeConfigFile(f);
                activeConfigFile = f;
                kubeConfigLabel.setText(f.getName());
            }
        }
        if (activeConfigFile == null) {
            // No override: the active config is default discovery's file (so the rail can match it).
            activeConfigFile = defaultKubeConfigFile();
        }
        // Migrate older prefs: the single remembered kube.conf becomes part of the loaded-files list,
        // so a file loaded before multi-config support still appears in the rail and can be removed.
        if (settings.kubeConfigPath != null && !settings.kubeConfigPath.isBlank()
                && !settings.kubeConfigPaths.contains(settings.kubeConfigPath)) {
            settings.kubeConfigPaths.add(settings.kubeConfigPath);
            saveSettings();
        }
    }

    /** Persist settings off the FX thread (best-effort). */
    private void saveSettings() {
        pool.submit(() -> settingsStore.save(settings));
    }

    private void persistHiddenColumns() {
        Map<String, List<String>> map = new HashMap<>();
        hiddenColumns.forEach((k, set) -> {
            if (!set.isEmpty()) {
                map.put(k.name(), new ArrayList<>(set));
            }
        });
        settings.hiddenColumns = map;
        saveSettings();
    }

    private void persistPortForwards() {
        List<Settings.PortForwardPref> prefs = new ArrayList<>();
        for (PortForward pf : portForwards) {
            prefs.add(new Settings.PortForwardPref(pf.context(), pf.namespace(), pf.pod(),
                    pf.remotePort(), pf.localPort()));
        }
        settings.portForwards = prefs;
        saveSettings();
    }

    /** Offer to re-open the port-forwards that were active at last exit (once, on startup). */
    private void restoreForwardsOnce() {
        if (forwardsRestored) {
            return;
        }
        forwardsRestored = true;
        List<Settings.PortForwardPref> saved = new ArrayList<>(settings.portForwards);
        if (saved.isEmpty()) {
            return;
        }
        boolean yes = confirm("Restore port-forwards?",
                "Re-open " + saved.size() + " port-forward(s) from your last session?");
        if (!yes) {
            settings.portForwards = new ArrayList<>();
            saveSettings();
            return;
        }
        for (Settings.PortForwardPref p : saved) {
            runAsync(() -> service.startPortForward(p.context(), p.namespace(), p.pod(),
                    p.remotePort(), p.localPort()), pf -> {
                portForwards.add(pf);
                updatePfButton();
                persistPortForwards();
            });
        }
    }

    // --- top bar ----------------------------------------------------------------------------

    private Region buildToolbar() {
        loadConfigBtn = new Button("Load kube.conf…");
        loadConfigBtn.getStyleClass().add("accent-button");
        loadConfigBtn.setOnAction(e -> chooseKubeConfig());

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
        // Keep the cluster rail in sync with the current context (selection + list changes).
        contextBox.valueProperty().addListener((o, a, b) -> refreshClusterRail());

        namespaceBox.setPromptText("Namespace");
        namespaceBox.setMinWidth(180);
        // Editable so you can type a namespace even when the token can't enumerate them (e.g. a
        // namespace-scoped token on AKS, where listing namespaces cluster-wide is forbidden).
        namespaceBox.setEditable(true);
        namespaceBox.setOnAction(e -> onSelectionChanged());

        searchField.setPromptText("Search…");
        searchField.setMinWidth(180);
        searchField.textProperty().addListener((o, a, b) -> applyFilter());

        Button refresh = new Button("↻");
        refresh.getStyleClass().add("icon-button");
        refresh.setOnAction(e -> refreshContent());

        pfButton.getStyleClass().add("icon-button");
        pfButton.setOnAction(e -> openForwardsManager());
        pfButton.setVisible(false);
        pfButton.setManaged(false);

        consoleMenuItem.setOnAction(e -> toggleConsole(consoleMenuItem.isSelected()));

        MenuButton appMenu = new MenuButton("☰");
        appMenu.getStyleClass().add("icon-button");
        appMenu.setTooltip(new Tooltip("Menu"));
        MenuItem mcpServerItem = new MenuItem("MCP server…");
        mcpServerItem.setOnAction(e -> McpServerPanel.show(getScene() == null ? null : getScene().getWindow(),
                mcpServer(), settings, () -> pool.submit(() -> settingsStore.save(settings))));
        MenuItem checkUpdates = new MenuItem("Check for updates…");
        checkUpdates.setOnAction(e -> checkForUpdates(true));
        MenuItem about = new MenuItem("About KubeDesk");
        about.setOnAction(e -> showAboutDialog());
        appMenu.getItems().setAll(consoleMenuItem, mcpServerItem, new SeparatorMenuItem(), checkUpdates,
                new SeparatorMenuItem(), about);

        liveLabel.getStyleClass().add("live-indicator");
        pauseButton.getStyleClass().add("live-toggle");
        pauseButton.setOnAction(e -> togglePause());
        pauseButton.setTooltip(new Tooltip("Pause live updates"));
        liveBox.setAlignment(Pos.CENTER_LEFT);
        liveBox.getChildren().setAll(liveLabel, pauseButton);
        liveBox.setVisible(false);
        liveBox.setManaged(false);

        // Label+combo pairs grouped so a wrap never splits a label from its control.
        HBox ctxGroup = new HBox(6, new Label("Context:"), contextBox);
        ctxGroup.setAlignment(Pos.CENTER_LEFT);
        HBox nsGroup = new HBox(6, new Label("Namespace:"), namespaceBox);
        nsGroup.setAlignment(Pos.CENTER_LEFT);

        // Left: cluster identity + selectors (a FlowPane so they wrap on a narrow window instead of
        // clipping). Right: search + actions + menu, pinned to the right edge.
        FlowPane left = new FlowPane(10, 8, loadConfigBtn, kubeConfigLabel, ctxGroup, nsGroup);
        left.setAlignment(Pos.CENTER_LEFT);
        HBox right = new HBox(10, searchField, refresh, liveBox, pfButton, appMenu);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.setMinWidth(Region.USE_PREF_SIZE); // never let the right cluster shrink below its controls

        BorderPane bar = new BorderPane();
        bar.setCenter(left);   // takes the remaining width and wraps within it
        bar.setRight(right);
        BorderPane.setAlignment(left, Pos.CENTER_LEFT);
        BorderPane.setAlignment(right, Pos.CENTER_RIGHT);
        BorderPane.setMargin(right, new Insets(0, 0, 0, 12));
        bar.setPadding(new Insets(10, 16, 10, 16));
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    /**
     * Show or hide the kubectl console drawer at the bottom of the center area. Built lazily on first
     * open. It reads the live context/namespace via suppliers, so it always targets what's selected now.
     */
    private void toggleConsole(boolean show) {
        if (show) {
            if (console == null) {
                console = new KubectlConsole(
                        service::activeKubeConfigFile,
                        () -> {
                            ContextInfo c = contextBox.getValue();
                            return c == null ? null : c.name();
                        },
                        this::effectiveNamespace);
                console.setOnClose(() -> toggleConsole(false));
            }
            if (!verticalSplit.getItems().contains(console)) {
                verticalSplit.getItems().add(console);
                verticalSplit.setDividerPositions(0.62);
            }
            console.onShown();
        } else if (console != null) {
            verticalSplit.getItems().remove(console);
        }
        consoleMenuItem.setSelected(show);
    }

    /**
     * The embedded MCP server, built on first use. It reads the live context/namespace via suppliers
     * (same pattern as the kubectl console), so a tool call defaults to what's selected now.
     */
    private KubeDeskMcpServer mcpServer() {
        if (mcpServer == null) {
            mcpServer = new KubeDeskMcpServer(
                    service,
                    UpdateService.currentVersion(),
                    () -> {
                        ContextInfo c = contextBox.getValue();
                        return c == null ? null : c.name();
                    },
                    this::effectiveNamespace);
            mcpServer.activity().addListener(this::onMcpActivity);
        }
        return mcpServer;
    }

    /**
     * The live mirror: reflect one agent tool call in the running window. Always echoes the call to
     * the status bar; when "follow" is on (and the call succeeded), also reveals the resource the
     * agent read — switching namespace/kind and opening its details — so you watch the agent work.
     */
    private void onMcpActivity(McpActivity.Event e) {
        Platform.runLater(() -> {
            setStatus("🤖 agent · " + e.summary() + (e.ok() ? " → " : " ✗ ") + e.outcome(),
                    !e.ok());
            if (settings.mcpFollow && e.ok() && e.navKind() != null) {
                followAgent(e);
            }
        });
    }

    /** Reveal, in the UI, the resource an agent call just touched. Runs on the FX thread. */
    private void followAgent(McpActivity.Event e) {
        if (e.navNamespace() != null && !e.navNamespace().isBlank()
                && !e.navNamespace().equals(namespaceBox.getValue())) {
            namespaceBox.setValue(e.navNamespace());
        }
        selectKind(e.navKind());               // switches nav + view and reloads the table
        if (e.navName() != null && !e.navName().isBlank()) {
            Tab target = "pod_logs".equals(e.tool()) ? logsTab : yamlTab;
            openDetails(e.navKind(), e.navName(), null, target);
        }
    }

    /**
     * Open the Ctrl/Cmd+P quick-switcher. Builds a fresh jump list each time so it reflects the
     * contexts/namespaces currently loaded; every entry just drives an existing handler.
     */
    private void openPalette() {
        if (palette == null) {
            palette = new CommandPalette();
        }
        if (palette.isShowing()) {
            palette.hide();
            return;
        }
        List<CommandPalette.Item> items = new ArrayList<>();

        // Resources — the most frequent jump.
        for (ResourceKind kind : ResourceKind.values()) {
            items.add(new CommandPalette.Item("Resource", kind.label(), () -> selectKind(kind)));
        }
        // Views.
        items.add(new CommandPalette.Item("View", "Overview", this::enterOverview));
        items.add(new CommandPalette.Item("View", "Health", this::enterHealth));
        items.add(new CommandPalette.Item("View", "Topology", this::enterTopology));
        items.add(new CommandPalette.Item("View", "Events", this::enterEvents));
        // Namespaces in the current context.
        for (String ns : namespaceBox.getItems()) {
            if (ns != null && !ns.isBlank()) {
                items.add(new CommandPalette.Item("Namespace", ns, () -> namespaceBox.setValue(ns)));
            }
        }
        // Clusters — every one in the rail (across all loaded kube.conf files). Searchable by the
        // context name, the custom label, and the environment; selecting switches and shows its Overview.
        for (ContextInfo c : allClusters) {
            if (c == null || settings.hiddenClusters.contains(c.name())) {
                continue;
            }
            String env = effectiveEnvironment(c.name());
            String label = settings.clusterLabels.get(c.name());
            StringBuilder text = new StringBuilder(c.name());
            if (label != null && !label.isBlank()) {
                text.append("  (").append(label).append(')');
            }
            if (env != null && !env.isBlank()) {
                text.append("  · ").append(env);
            }
            items.add(new CommandPalette.Item("Cluster", text.toString(), () -> switchAndSummarize(c)));
        }

        palette.show(this, items);
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
            // Remember the file so its clusters stay in the rail; switch to it as the active config.
            String path = chosen.getAbsolutePath();
            if (!settings.kubeConfigPaths.contains(path)) {
                settings.kubeConfigPaths.add(path);
            }
            service.setKubeConfigFile(chosen);
            activeConfigFile = chosen;
            kubeConfigLabel.setText(chosen.getName());
            settings.kubeConfigPath = path;
            saveSettings();
            status("Loaded kube.conf: " + path);
            // Land on the Overview so the just-registered cluster shows its summary once its
            // namespaces resolve (loadContexts → loadNamespaces → onSelectionChanged → loadOverview).
            setCenterView(ViewMode.OVERVIEW);
            overviewToggle.setSelected(true);
            closeDetails();
            stopWatch();
            loadContexts();
            reloadClusters();
        }
    }

    // --- sidebar ----------------------------------------------------------------------------

    private Region buildSidebar() {
        VBox nav = new VBox(4);
        nav.getStyleClass().add("sidebar");
        nav.setPadding(new Insets(16, 8, 16, 8));
        nav.setMinWidth(190);

        ToggleGroup group = new ToggleGroup();

        // Overview dashboard sits at the very top, above the resource kinds.
        overviewToggle.setToggleGroup(group);
        overviewToggle.setMaxWidth(Double.MAX_VALUE);
        overviewToggle.getStyleClass().add("nav-item");
        overviewToggle.setOnAction(e -> {
            if (overviewToggle.isSelected()) {
                enterOverview();
            }
        });
        nav.getChildren().add(overviewToggle);

        String lastSection = null;
        for (ResourceKind kind : ResourceKind.values()) {
            if (!kind.section().equals(lastSection)) {
                Label section = new Label(kind.section().toUpperCase());
                section.getStyleClass().add("nav-section");
                nav.getChildren().add(section);
                sectionLabels.put(kind.section(), section);
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
                    selectKind(kind);
                }
            });
            navButtons.put(kind, btn);
            nav.getChildren().add(btn);
        }

        // A non-resource "view": the namespace topology map.
        Label viewsSection = new Label("VIEWS");
        viewsSection.getStyleClass().add("nav-section");
        nav.getChildren().add(viewsSection);
        healthToggle.setToggleGroup(group);
        healthToggle.setMaxWidth(Double.MAX_VALUE);
        healthToggle.getStyleClass().add("nav-item");
        healthToggle.setOnAction(e -> {
            if (healthToggle.isSelected()) {
                enterHealth();
            }
        });
        nav.getChildren().add(healthToggle);

        topologyToggle.setToggleGroup(group);
        topologyToggle.setMaxWidth(Double.MAX_VALUE);
        topologyToggle.getStyleClass().add("nav-item");
        topologyToggle.setOnAction(e -> {
            if (topologyToggle.isSelected()) {
                enterTopology();
            }
        });
        nav.getChildren().add(topologyToggle);

        eventsToggle.setToggleGroup(group);
        eventsToggle.setMaxWidth(Double.MAX_VALUE);
        eventsToggle.getStyleClass().add("nav-item");
        eventsToggle.setOnAction(e -> {
            if (eventsToggle.isSelected()) {
                enterEvents();
            }
        });
        nav.getChildren().add(eventsToggle);

        // Make the nav scrollable so a short window (e.g. a small VDI screen) shrinks/scrolls the
        // list instead of forcing the whole layout taller than the screen and hiding the status bar.
        ScrollPane scroll = new ScrollPane(nav);
        scroll.getStyleClass().add("sidebar-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setMinHeight(0);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // Pin the global "+ Create" action to the bottom, outside the scroll area (it applies any kind).
        createBtn = new Button("+ Create");
        createBtn.getStyleClass().add("accent-button");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setOnAction(e -> openCreateDialog());
        VBox createBox = new VBox(createBtn);
        createBox.setPadding(new Insets(10, 12, 12, 12));

        // Brand at the top of the sidebar (the old top-bar logo position).
        Label brand = new Label("KubeDesk");
        brand.getStyleClass().add("brand");
        VBox brandBox = new VBox(brand);
        brandBox.setPadding(new Insets(14, 12, 8, 14));

        VBox left = new VBox(brandBox, scroll, createBox);
        left.getStyleClass().add("sidebar-container");
        left.setMinHeight(0);
        left.setMinWidth(190);
        return left;
    }

    // --- cluster rail (left-edge quick switch) ----------------------------------------------

    /** Pleasant, high-contrast palette; a context always maps to the same color by its name hash. */
    private static final String[] CLUSTER_COLORS = {
            "#326ce5", "#2e7d32", "#ed6c02", "#6b46c1", "#0891b2",
            "#c2185b", "#00838f", "#5d4037", "#7b1fa2", "#f57f17"
    };

    private Region buildClusterRail() {
        clusterRail.getStyleClass().add("cluster-rail");
        clusterRail.setAlignment(Pos.TOP_CENTER);

        ScrollPane scroll = new ScrollPane(clusterRail);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("cluster-rail-scroll");
        scroll.setMinHeight(0);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // "+" loads another kube.conf (same action as the top-bar button).
        Button add = new Button("+");
        add.getStyleClass().add("cluster-add");
        add.setTooltip(new Tooltip("Load another kube.conf…"));
        add.setOnAction(e -> chooseKubeConfig());

        railBody = new VBox(10, scroll, add);
        railBody.setAlignment(Pos.TOP_CENTER);
        railBody.setMinHeight(0);
        VBox.setVgrow(railBody, Priority.ALWAYS);

        // Chevron to collapse/expand the rail (state persisted).
        railToggle = new Button("‹");
        railToggle.getStyleClass().add("rail-toggle");
        railToggle.setOnAction(e -> setRailCollapsed(!settings.clusterRailCollapsed));

        railWrap = new VBox(8, railToggle, railBody);
        railWrap.setAlignment(Pos.TOP_CENTER);
        railWrap.getStyleClass().add("cluster-rail-wrap");
        railWrap.setPadding(new Insets(10, 8, 12, 8));
        railWrap.setMinHeight(0);
        applyRailCollapsed(settings.clusterRailCollapsed);
        return railWrap;
    }

    private void setRailCollapsed(boolean collapsed) {
        settings.clusterRailCollapsed = collapsed;
        settingsStore.save(settings);
        applyRailCollapsed(collapsed);
    }

    private void applyRailCollapsed(boolean collapsed) {
        railBody.setVisible(!collapsed);
        railBody.setManaged(!collapsed);
        railToggle.setText(collapsed ? "›" : "‹");
        railToggle.setTooltip(new Tooltip(collapsed ? "Show the cluster rail" : "Collapse the cluster rail"));
        double w = collapsed ? 28 : 60;
        railWrap.setMinWidth(w);
        railWrap.setPrefWidth(w);
        railWrap.setMaxWidth(w);
    }

    /**
     * Re-read every loaded kube.conf (default discovery + each remembered file) and aggregate their
     * contexts into the rail. One cluster per name (the first source wins); each remembers its file.
     */
    private void reloadClusters() {
        // Always read the TRUE default config (not the active override) + each remembered file.
        final List<File> sources = new ArrayList<>();
        sources.add(defaultKubeConfigFile());
        for (String p : settings.kubeConfigPaths) {
            if (p != null && !p.isBlank()) {
                File f = new File(p);
                if (sources.stream().noneMatch(s -> sameFile(s, f))) {
                    sources.add(f);
                }
            }
        }
        runAsync(() -> {
            List<Object[]> pairs = new ArrayList<>();
            for (File f : sources) {
                try {
                    for (ContextInfo c : service.listContexts(f)) {
                        pairs.add(new Object[]{c, f});
                    }
                } catch (Exception ignore) {
                    // a missing/broken file just contributes nothing to the rail
                }
            }
            return pairs;
        }, pairs -> {
            allClusters.clear();
            clusterFiles.clear();
            for (Object[] p : pairs) {
                ContextInfo c = (ContextInfo) p[0];
                if (!clusterFiles.containsKey(c.name())) { // first source wins on a name clash
                    clusterFiles.put(c.name(), (File) p[1]);
                    allClusters.add(c);
                }
            }
            refreshClusterRail();
        });
    }

    /** Rebuild the rail's avatars from the aggregated cluster list, honoring label/color/hidden overrides. */
    private void refreshClusterRail() {
        clusterRail.getChildren().clear();
        ContextInfo current = contextBox.getValue();
        int hidden = 0;
        for (ContextInfo c : allClusters) {
            if (settings.hiddenClusters.contains(c.name())) {
                hidden++;
                continue;
            }
            Label avatar = new Label(clusterLabel(c.name()));
            avatar.getStyleClass().add("cluster-avatar");
            avatar.setStyle("-fx-background-color: " + clusterColorFor(c.name()) + ";");
            if (current != null && current.name().equals(c.name())) {
                avatar.getStyleClass().add("cluster-avatar-active");
            }
            String env = effectiveEnvironment(c.name());
            avatar.setTooltip(new Tooltip(c.name() + (env != null && !env.isBlank() ? "  ·  " + env : "")
                    + "\n(click to switch · right-click to edit)"));
            // Left-click: switch the whole UI to this cluster and show its Overview.
            avatar.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    switchAndSummarize(c);
                }
            });
            // Right-click: open the editor (label / color / environment / forget).
            avatar.setOnContextMenuRequested(e -> {
                openClusterDialog(c);
                e.consume();
            });
            clusterRail.getChildren().add(avatar);
        }
        if (hidden > 0) {
            Label more = new Label("⋯");
            more.getStyleClass().add("cluster-add");
            more.setTooltip(new Tooltip(hidden + " hidden — click to manage"));
            // A menu (not a one-click unhide-all) so a stray click never wipes the hidden set.
            ContextMenu hiddenMenu = new ContextMenu();
            for (ContextInfo c : allClusters) {
                if (settings.hiddenClusters.contains(c.name())) {
                    MenuItem show = new MenuItem("Show " + c.name());
                    show.setOnAction(e -> {
                        settings.hiddenClusters.remove(c.name());
                        settingsStore.save(settings);
                        refreshClusterRail();
                    });
                    hiddenMenu.getItems().add(show);
                }
            }
            MenuItem showAll = new MenuItem("Show all hidden");
            showAll.setOnAction(e -> {
                settings.hiddenClusters.clear();
                settingsStore.save(settings);
                refreshClusterRail();
            });
            hiddenMenu.getItems().addAll(new SeparatorMenuItem(), showAll);
            more.setOnMouseClicked(e -> hiddenMenu.show(more, e.getScreenX(), e.getScreenY()));
            clusterRail.getChildren().add(more);
        }
        updateChrome();
    }

    /** Keep the empty-state "Load kube.conf…" button and the top accent strip in sync with state. */
    private void updateChrome() {
        boolean empty = allClusters.isEmpty();
        if (loadConfigBtn != null) {
            loadConfigBtn.setVisible(empty);
            loadConfigBtn.setManaged(empty);
            kubeConfigLabel.setVisible(empty);
            kubeConfigLabel.setManaged(empty);
        }
        ContextInfo cur = contextBox.getValue();
        String color = cur != null ? clusterColorFor(cur.name()) : "transparent";
        clusterAccent.setStyle("-fx-background-color: " + color + ";");
    }

    /** The one place to manage a cluster: its config file, label, color, environment — and switch to it. */
    private void openClusterDialog(ContextInfo c) {
        String name = c.name();
        File src = clusterFiles.get(name);
        boolean fromLoadedFile = src != null
                && settings.kubeConfigPaths.stream().anyMatch(p -> sameFile(new File(p), src))
                && !sameFile(src, defaultKubeConfigFile());
        String fileText = (src == null || sameFile(src, defaultKubeConfigFile()))
                ? "default  (~/.kube/config)" : src.getAbsolutePath();

        Dialog<ButtonType> dialog = new Dialog<>();
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.setTitle("Cluster");
        dialog.setHeaderText(name);
        var css = getClass().getResource("/styles/app.css");
        if (css != null) {
            dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        }

        Label fileVal = new Label(fileText);
        fileVal.getStyleClass().add("muted");
        fileVal.setWrapText(true);
        fileVal.setMaxWidth(380);

        TextField labelField = new TextField(settings.clusterLabels.getOrDefault(name, clusterInitials(name)));
        labelField.setPromptText("short label, e.g. DEV / PRD");

        ComboBox<String> envBox = new ComboBox<>(FXCollections.observableArrayList(
                "(none)", "DEV", "DEMO", "STAGING", "PROD"));
        envBox.setEditable(true);
        String curEnv = effectiveEnvironment(name); // shows the inferred value when none is set
        envBox.setValue(curEnv == null || curEnv.isBlank() ? "(none)" : curEnv);

        // Color: an "Auto" option (environment/hash) plus the palette swatches.
        ToggleGroup colorGroup = new ToggleGroup();
        FlowPane colorFlow = new FlowPane(6, 6);
        String curColor = settings.clusterColors.get(name);
        ToggleButton auto = new ToggleButton("Auto");
        auto.setToggleGroup(colorGroup);
        auto.setSelected(curColor == null || curColor.isBlank());
        colorFlow.getChildren().add(auto);
        for (String col : CLUSTER_COLORS) {
            ToggleButton t = new ToggleButton();
            t.setToggleGroup(colorGroup);
            t.setUserData(col);
            t.setMinSize(22, 22);
            t.setPrefSize(22, 22);
            t.setStyle("-fx-background-color: " + col + "; -fx-background-radius: 5;");
            if (col.equals(curColor)) {
                t.setSelected(true);
            }
            colorFlow.getChildren().add(t);
        }

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(6, 2, 2, 2));
        grid.addRow(0, new Label("Config file:"), fileVal);
        grid.addRow(1, new Label("Label:"), labelField);
        grid.addRow(2, new Label("Environment:"), envBox);
        grid.addRow(3, new Label("Color:"), colorFlow);
        dialog.getDialogPane().setContent(grid);

        ButtonType switchBtn = new ButtonType("Switch to this cluster", ButtonBar.ButtonData.OK_DONE);
        ButtonType saveBtn = new ButtonType("Save", ButtonBar.ButtonData.APPLY);
        ButtonType hideBtn = new ButtonType("Hide", ButtonBar.ButtonData.LEFT);
        ButtonType forgetBtn = new ButtonType("Forget this kube.conf", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().add(switchBtn);
        dialog.getDialogPane().getButtonTypes().add(saveBtn);
        dialog.getDialogPane().getButtonTypes().add(hideBtn);
        if (fromLoadedFile) {
            dialog.getDialogPane().getButtonTypes().add(forgetBtn);
        }
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result == ButtonType.CANCEL) {
            return;
        }
        if (result == hideBtn) {
            if (!settings.hiddenClusters.contains(name)) {
                settings.hiddenClusters.add(name);
            }
            settingsStore.save(settings);
            refreshClusterRail();
            return;
        }
        if (result == forgetBtn) {
            removeKubeConfigFile(src);
            return;
        }
        // Save or Switch: persist label / environment / color from the fields.
        String label = labelField.getText() == null ? "" : labelField.getText().trim();
        if (label.isEmpty()) {
            settings.clusterLabels.remove(name);
        } else {
            settings.clusterLabels.put(name, label);
        }
        String env = envBox.getValue() == null ? "" : envBox.getValue().trim();
        if (env.isEmpty() || "(none)".equals(env)) {
            settings.clusterEnvironments.remove(name);
        } else {
            settings.clusterEnvironments.put(name, env);
        }
        var sel = colorGroup.getSelectedToggle();
        String color = sel == null ? null : (String) sel.getUserData();
        if (color == null) {
            settings.clusterColors.remove(name); // Auto (environment/hash)
        } else {
            settings.clusterColors.put(name, color);
        }
        settingsStore.save(settings);
        refreshClusterRail();
        if (result == switchBtn) {
            switchAndSummarize(c); // switch and land on the cluster's Overview summary
        }
    }

    /** Forget a loaded kube.conf: drop it from the remembered list and re-aggregate the rail. */
    private void removeKubeConfigFile(File file) {
        settings.kubeConfigPaths.remove(file.getAbsolutePath());
        settingsStore.save(settings);
        // If it was the active config, fall back to default discovery.
        if (sameFile(file, activeConfigFile)) {
            service.setKubeConfigFile(null);
            activeConfigFile = null;
            settings.kubeConfigPath = null;
            settingsStore.save(settings);
            kubeConfigLabel.setText("(default ~/.kube/config)");
            loadContexts();
        }
        reloadClusters();
        status("Removed kube.conf: " + file.getAbsolutePath());
    }

    /** Left-click behavior: switch the whole UI to this cluster and land on its Overview summary. */
    private void switchAndSummarize(ContextInfo c) {
        boolean alreadyActive = contextBox.getValue() != null
                && c.name().equals(contextBox.getValue().name())
                && sameFile(clusterFiles.get(c.name()), activeConfigFile);
        setCenterView(ViewMode.OVERVIEW);
        overviewToggle.setSelected(true);
        closeDetails();
        stopWatch();
        if (alreadyActive) {
            loadOverview(); // already on this cluster — just (re)show the summary
        } else {
            switchToCluster(c); // changes context → loadNamespaces → onSelectionChanged → loadOverview
        }
    }

    /** Switch the active cluster, loading its kube.conf file first if it differs from the active one. */
    private void switchToCluster(ContextInfo c) {
        File target = clusterFiles.get(c.name());
        if (sameFile(target, activeConfigFile)) {
            // Same file already active — just select the context in the dropdown.
            contextBox.getItems().stream()
                    .filter(x -> x.name().equals(c.name())).findFirst()
                    .ifPresent(match -> {
                        if (!match.equals(contextBox.getValue())) {
                            contextBox.setValue(match); // fires onContextChosen → loadNamespaces
                        }
                    });
            return;
        }
        if (target != null && !target.exists()) {
            statusError("kube.conf for '" + c.name() + "' not found at " + target.getAbsolutePath()
                    + " — it may have moved.");
            return;
        }
        // Point the service at the target file, reload its contexts into the dropdown, then select c.
        service.setKubeConfigFile(target);
        activeConfigFile = target;
        settings.kubeConfigPath = target == null ? null : target.getAbsolutePath();
        saveSettings();
        kubeConfigLabel.setText(target == null ? "(default ~/.kube/config)" : target.getName());
        final String want = c.name();
        runAsync(service::listContexts, ctxs -> {
            contextBox.setOnAction(null);
            contextBox.setItems(FXCollections.observableArrayList(ctxs));
            ContextInfo match = ctxs.stream().filter(x -> x.name().equals(want)).findFirst()
                    .orElse(ctxs.isEmpty() ? null : ctxs.get(0));
            contextBox.setValue(match);
            contextBox.setOnAction(ev -> onContextChosen());
            refreshClusterRail();
            if (match != null) {
                loadNamespaces(match);
            }
        });
    }

    /** The kube.conf that default discovery would use: $KUBECONFIG's first entry, else ~/.kube/config. */
    private static File defaultKubeConfigFile() {
        String env = System.getenv("KUBECONFIG");
        if (env != null && !env.isBlank()) {
            String first = env.split(File.pathSeparator)[0].trim();
            if (!first.isEmpty()) {
                return new File(first);
            }
        }
        return new File(System.getProperty("user.home"), ".kube" + File.separator + "config");
    }

    /** Two kube.conf file references point at the same config (both null = default discovery). */
    private static boolean sameFile(File a, File b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    private String clusterLabel(String name) {
        String override = settings.clusterLabels.get(name);
        return override != null && !override.isBlank() ? override : clusterInitials(name);
    }

    /** Avatar color: explicit override, else the environment's color (PROD=red, …), else a name hash. */
    private String clusterColorFor(String name) {
        String override = settings.clusterColors.get(name);
        if (override != null && !override.isBlank()) {
            return override;
        }
        String envColor = environmentColor(effectiveEnvironment(name));
        return envColor != null ? envColor : clusterColor(name);
    }

    /** The environment to use: the user's explicit choice if set, else one inferred from the name. */
    private String effectiveEnvironment(String name) {
        String override = settings.clusterEnvironments.get(name);
        if (override != null && !override.isBlank()) {
            return override;
        }
        return inferEnvironment(name);
    }

    /** Guess an environment from whole words in the context name (prod wins; no substring false hits). */
    private static String inferEnvironment(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String s = "-" + name.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-";
        if (s.contains("-prod-") || s.contains("-production-") || s.contains("-prd-")) {
            return "PROD";
        }
        if (s.contains("-staging-") || s.contains("-stage-") || s.contains("-stg-")) {
            return "STAGING";
        }
        if (s.contains("-demo-")) {
            return "DEMO";
        }
        if (s.contains("-dev-") || s.contains("-development-") || s.contains("-develop-")) {
            return "DEV";
        }
        return null;
    }

    private static String environmentColor(String env) {
        if (env == null) {
            return null;
        }
        return switch (env.trim().toUpperCase()) {
            case "PROD", "PRODUCTION" -> "#e11900"; // red
            case "STAGING", "STAGE" -> "#f9a825";   // amber
            case "DEMO" -> "#ed6c02";               // orange
            case "DEV", "DEVELOPMENT" -> "#2e7d32"; // green
            default -> null;
        };
    }

    private static String clusterColor(String name) {
        return CLUSTER_COLORS[Math.floorMod(name.hashCode(), CLUSTER_COLORS.length)];
    }

    /** Two-letter badge: first letters of the first two name parts, else the first two characters. */
    private static String clusterInitials(String name) {
        String[] parts = name.split("[-_.:/ ]+");
        StringBuilder sb = new StringBuilder();
        if (parts.length >= 2) {
            for (int i = 0; i < parts.length && sb.length() < 2; i++) {
                if (!parts[i].isEmpty()) {
                    sb.append(Character.toUpperCase(parts[i].charAt(0)));
                }
            }
        } else if (parts.length == 1 && !parts[0].isEmpty()) {
            sb.append(parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase());
        }
        return sb.length() == 0 ? "?" : sb.toString();
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

        topologyView.setVisible(false);
        topologyView.setManaged(false);
        VBox.setVgrow(topologyView, Priority.ALWAYS);

        buildHealthView();
        healthView.setVisible(false);
        healthView.setManaged(false);
        VBox.setVgrow(healthView, Priority.ALWAYS);

        buildEventsView();
        eventsView.setVisible(false);
        eventsView.setManaged(false);
        VBox.setVgrow(eventsView, Priority.ALWAYS);

        buildOverviewView();
        overviewView.setVisible(false);
        overviewView.setManaged(false);
        VBox.setVgrow(overviewView, Priority.ALWAYS);

        VBox content = new VBox(12, contentTitle, healthRing, table, topologyView, healthView,
                eventsView, overviewView);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("content");
        content.setMinWidth(320); // keep the table usable no matter how wide the details pane gets
        return content;
    }

    private HBox buildStatusBar() {
        // Selectable + copyable: read-only field styled to look like a plain status label.
        statusBar.setEditable(false);
        statusBar.setFocusTraversable(false);
        statusBar.getStyleClass().add("status-text");
        HBox box = new HBox(statusBar);
        HBox.setHgrow(statusBar, Priority.ALWAYS);
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

        MenuItem portForward = new MenuItem("Port forward…");
        portForward.setOnAction(e -> openPortForwardDialog(resourceName(row.getItem())));

        MenuItem copyName = new MenuItem("Copy name");
        copyName.setOnAction(e -> copyToClipboard(resourceName(row.getItem())));

        MenuItem copyYaml = new MenuItem("Copy YAML");
        copyYaml.setOnAction(e -> copyYaml(currentKind, resourceName(row.getItem())));

        // Mutating actions
        MenuItem restart = new MenuItem("Restart (rollout)");
        restart.setOnAction(e -> restartAction(resourceName(row.getItem())));

        MenuItem restartPod = new MenuItem("Restart (recreate)");
        restartPod.setOnAction(e -> restartPodAction(resourceName(row.getItem())));

        MenuItem scale = new MenuItem("Scale…");
        scale.setOnAction(e -> scaleAction(resourceName(row.getItem())));

        MenuItem delete = new MenuItem("Delete");
        delete.getStyleClass().add("danger-item");
        delete.setOnAction(e -> deleteAction(currentKind, resourceName(row.getItem())));

        ContextMenu menu = new ContextMenu(details, yaml, events, logs, portForward,
                new SeparatorMenuItem(), copyName, copyYaml,
                new SeparatorMenuItem(), restart, restartPod, scale, delete);
        menu.setOnShowing(e -> {
            // Tailor the menu to the resource kind: hide (not just disable) what doesn't apply.
            boolean isPod = currentKind == ResourceKind.PODS;
            logs.setVisible(isPod);
            portForward.setVisible(isPod);
            events.setVisible(kindHasEvents(currentKind));
            boolean isDeployment = currentKind == ResourceKind.DEPLOYMENTS;
            restart.setVisible(isDeployment);
            scale.setVisible(isDeployment);
            // A pod "restart" is delete-and-let-the-controller-recreate, so only offer it for managed pods.
            restartPod.setVisible(isPod && isControlledPod(row.getItem()));
            // Disable mutating actions the user isn't allowed to perform (RBAC-aware).
            String g = currentKind.apiGroup(), r = currentKind.resource();
            delete.setDisable(!currentPermissions.can(g, r, "delete"));
            restartPod.setDisable(!currentPermissions.can(g, r, "delete"));
            boolean canPatch = currentPermissions.can(g, r, "patch")
                    || currentPermissions.can(g, r, "update");
            restart.setDisable(!canPatch);
            scale.setDisable(!canPatch);
        });

        // A read-only menu for "All namespaces" mode (act on a resource by picking its namespace).
        MenuItem allYaml = new MenuItem("View YAML");
        allYaml.setOnAction(e -> openYamlDialog(currentKind, nameOfRow(row.getItem()),
                namespaceOfRow(row.getItem())));
        MenuItem allCopyName = new MenuItem("Copy name");
        allCopyName.setOnAction(e -> copyToClipboard(nameOfRow(row.getItem())));
        MenuItem goToNs = new MenuItem("Go to this namespace");
        goToNs.setOnAction(e -> namespaceBox.setValue(namespaceOfRow(row.getItem())));
        ContextMenu allMenu = new ContextMenu(allYaml, allCopyName, new SeparatorMenuItem(), goToNs);

        // Only show a menu on non-empty rows; use the read-only menu in all-namespaces mode.
        row.contextMenuProperty().bind(
                Bindings.when(row.emptyProperty()).then((ContextMenu) null)
                        .otherwise(Bindings.when(allNsMode).then(allMenu).otherwise(menu)));

        row.setOnMouseClicked(ev -> {
            if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 2 && !row.isEmpty()) {
                if (allNsMode.get()) {
                    openYamlDialog(currentKind, nameOfRow(row.getItem()),
                            namespaceOfRow(row.getItem()));
                } else {
                    showDetails(row.getItem(), overviewTab);
                }
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

        detailsActionsBtn.getStyleClass().add("accent-button");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox titleBox = new VBox(2, detailsTitle, detailsSubtitle);
        HBox header = new HBox(8, titleBox, spacer, detailsActionsBtn, close);
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
        eventsTab.setContent(buildEventsPane());
        logsTab.setClosable(false);
        logsTab.setContent(buildLogsPane());
        shellTab.setClosable(false);
        shellTab.setContent(buildShellPane());
        metricsTab.setClosable(false);
        metricsTab.setContent(buildMetricsPane());
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
        runAsync(() -> service.describeYaml(ctx.name(), namespaceBox.getValue(), detailKind, currentDetailName),
                yamlArea::setText);
    }

    /** The Events tab content: a refresh + copy toolbar above the read-only event text. */
    private VBox buildEventsPane() {
        Button refresh = new Button("↻");
        refresh.getStyleClass().add("icon-button");
        refresh.setTooltip(new Tooltip("Reload events"));
        refresh.setOnAction(e -> reloadEvents());

        Button copy = copyButton("Copy all events to the clipboard", eventsArea::getText, "events");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox controls = new HBox(8, refresh, spacer, copy);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8, 12, 8, 12));

        VBox.setVgrow(eventsArea, Priority.ALWAYS);
        return new VBox(controls, eventsArea);
    }

    private void reloadEvents() {
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null || currentDetailName == null) {
            return;
        }
        eventsArea.setText("Loading…");
        runAsync(() -> service.describeEvents(ctx.name(), namespaceBox.getValue(), detailKind, currentDetailName),
                eventsArea::setText);
    }

    /**
     * A "⧉ Copy" toolbar button that copies whatever {@code text} yields when clicked (so it always
     * grabs the current content, not a snapshot) and confirms in the status bar.
     */
    private Button copyButton(String tooltip, Supplier<String> text, String what) {
        Button copy = new Button("⧉ Copy");
        copy.getStyleClass().add("icon-button");
        copy.setTooltip(new Tooltip(tooltip));
        copy.setOnAction(e -> {
            String value = text.get();
            if (value == null || value.isBlank() || "Loading…".equals(value)) {
                status("Nothing to copy yet.");
                return;
            }
            copyToClipboard(value);
            long lines = value.lines().count();
            status("Copied " + lines + (lines == 1 ? " line" : " lines") + " of " + what + " to the clipboard.");
        });
        return copy;
    }

    /** The Logs tab content: container picker + tail-size picker + refresh + a monospace log view. */
    private VBox buildLogsPane() {
        containerBox.setMinWidth(150);
        containerBox.setOnAction(e -> loadLogs());

        tailBox.setItems(FXCollections.observableArrayList(100, 500, 1000, 5000));
        tailBox.setValue(500);
        tailBox.setOnAction(e -> loadLogs());

        // A colored dot reflecting the pod's phase (green Running, amber Pending, red failing).
        logStatusDot.getStyleClass().add("status-dot");
        logStatusDot.setVisible(false);

        Button refresh = new Button("↻");
        refresh.getStyleClass().add("icon-button");
        refresh.setOnAction(e -> loadLogs());

        // Follow = live tail (kubectl logs -f), rendered as a play/pause button: ▶ starts the stream,
        // ⏸ (shown while following) stops it. Toggling re-runs loadLogs(), which starts or stops the
        // stream based on this button's state; the glyph + tooltip track the state.
        followToggle.getStyleClass().add("log-follow");
        followToggle.setOnAction(e -> loadLogs());
        followToggle.selectedProperty().addListener((o, was, following) -> {
            followToggle.setText(following ? "⏸" : "▶");
            followToggle.setTooltip(new Tooltip(following ? "Stop following" : "Follow live (kubectl logs -f)"));
        });
        followToggle.setText("▶");
        followToggle.setTooltip(new Tooltip("Follow live (kubectl logs -f)"));

        Button detach = new Button("⧉");
        detach.getStyleClass().add("icon-button");
        detach.setTooltip(new Tooltip("Open these logs in a separate window (keeps streaming while you work)"));
        detach.setOnAction(e -> detachLogs());

        // Previous = logs of the prior (terminated) container — the way to see why a pod crashed.
        // A dead container can't stream, so turning this on disables Follow.
        previousCheck.setTooltip(new Tooltip("Show the previous (crashed/restarted) container's logs"));
        previousCheck.setOnAction(e -> loadLogs());
        followToggle.disableProperty().bind(previousCheck.selectedProperty());

        // Timestamps = prefix each line with the server's timestamp (works live and one-shot).
        timestampsCheck.setTooltip(new Tooltip("Prefix each line with the server timestamp"));
        timestampsCheck.setOnAction(e -> loadLogs());

        Button save = new Button("Save…");
        save.getStyleClass().add("accent-button");
        save.setOnAction(e -> saveLogs());

        Button copy = copyButton("Copy the whole log tail to the clipboard", logBuffer::raw, "logs");

        // Filter: a case-insensitive "show only matching lines" box. The full log is kept in logBuffer,
        // so filtering only hides lines from view (Copy/Save still get everything).
        TextField filterField = new TextField();
        filterField.setPromptText("Filter lines…");
        filterField.setPrefColumnCount(14);
        filterField.textProperty().addListener((o, a, b) -> logBuffer.setFilter(b));

        // Wrap long lines on/off.
        ToggleButton wrap = new ToggleButton("⤶");
        wrap.getStyleClass().add("log-follow");
        wrap.setTooltip(new Tooltip("Wrap long lines"));
        logsArea.wrapTextProperty().bind(wrap.selectedProperty());

        // Clear the current view (the stream, if following, keeps appending new lines after).
        Button clear = new Button("⌫");
        clear.getStyleClass().add("icon-button");
        clear.setTooltip(new Tooltip("Clear the log view"));
        clear.setOnAction(e -> logBuffer.clear());

        // A FlowPane so the toolbar wraps to a second line on a narrow window instead of clipping.
        // Label+combo pairs are grouped so they never split across the wrap.
        HBox containerGroup = new HBox(6, new Label("Container:"), containerBox);
        containerGroup.setAlignment(Pos.CENTER_LEFT);
        HBox tailGroup = new HBox(6, new Label("Tail:"), tailBox);
        tailGroup.setAlignment(Pos.CENTER_LEFT);

        FlowPane controls = new FlowPane(8, 8,
                logStatusDot, containerGroup, tailGroup, refresh, followToggle, previousCheck,
                timestampsCheck, filterField, wrap, clear, copy, save, detach);
        controls.getStyleClass().add("log-toolbar");
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8, 12, 8, 12));

        logsArea.setEditable(false);
        logsArea.getStyleClass().addAll("yaml-area", "logs-area");

        // A "jump to latest" pill floats over the bottom-right of the log view while you're scrolled
        // up; clicking it re-pins to the newest line. Hidden whenever you're already at the bottom.
        Button jump = new Button("↓ Jump to latest");
        jump.getStyleClass().add("jump-latest");
        jump.setOnAction(e -> logTail.scrollToBottom());
        jump.visibleProperty().bind(logTail.atBottomProperty().not());
        jump.managedProperty().bind(jump.visibleProperty());
        StackPane.setAlignment(jump, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(jump, new Insets(0, 16, 16, 0));

        StackPane logStack = new StackPane(logsArea, jump);
        VBox.setVgrow(logStack, Priority.ALWAYS);

        VBox pane = new VBox(controls, logStack);
        return pane;
    }

    /** Open details for a table row (uses the current kind and the row's cell values for Overview). */
    private void showDetails(List<String> row, Tab target) {
        if (row == null) {
            return;
        }
        openDetails(currentKind, resourceName(row), row, target);
    }

    /** Open details for a resource by kind + name (used by the topology view; row may be null). */
    private void showDetailsFor(ResourceKind kind, String name) {
        openDetails(kind, name, null, yamlTab);
    }

    /**
     * Populate and reveal the details panel for a resource. When {@code row} is non-null its cell
     * values populate the Overview; otherwise only the name is shown (YAML has the full picture).
     */
    private void openDetails(ResourceKind kind, String name, List<String> row, Tab target) {
        if (name == null || name.isEmpty()) {
            return;
        }
        detailKind = kind;
        currentDetailName = name;
        detailsTitle.setText(name);
        detailsSubtitle.setText(kind.label() + (kind.namespaced() ? " · " + safeNamespace() : ""));
        populateDetailsActions(kind, name);

        // Overview: from the row's cells when we have them, else just the name. Values are selectable
        // read-only fields, so you can select + Ctrl+C or right-click → Copy to share them.
        overviewBox.getChildren().clear();
        StringBuilder all = new StringBuilder();
        if (row != null) {
            List<String> cols = currentData.columns();
            for (int i = 0; i < cols.size(); i++) {
                String value = i < row.size() ? row.get(i) : "";
                all.append(cols.get(i)).append(": ").append(value).append('\n');
                Label key = new Label(cols.get(i));
                key.getStyleClass().add("kv-key");
                key.setMinWidth(110);
                HBox line = new HBox(10, key, copyableValue(value));
                line.setAlignment(Pos.CENTER_LEFT);
                overviewBox.getChildren().add(line);
            }
        } else {
            all.append("Name: ").append(name).append('\n');
            Label key = new Label("Name");
            key.getStyleClass().add("kv-key");
            key.setMinWidth(110);
            Label hint = new Label("See the YAML tab for full details.");
            hint.getStyleClass().add("muted");
            overviewBox.getChildren().addAll(new HBox(10, key, copyableValue(name)), hint);
        }
        // A "Copy all" button above the fields, to grab the whole Overview at once.
        String allText = all.toString();
        Button copyAll = new Button("⧉ Copy all");
        copyAll.getStyleClass().add("icon-button");
        copyAll.setTooltip(new Tooltip("Copy all fields to the clipboard"));
        copyAll.setOnAction(e -> copyToClipboard(allText));
        Region copyAllSpacer = new Region();
        HBox.setHgrow(copyAllSpacer, Priority.ALWAYS);
        overviewBox.getChildren().add(0, new HBox(copyAllSpacer, copyAll));

        // Build the tab set for this kind: Overview + YAML always; Events when applicable; Logs for pods.
        boolean isPod = kind == ResourceKind.PODS;
        detailsTabs.getTabs().setAll(overviewTab, yamlTab);
        if (kindHasEvents(kind)) {
            detailsTabs.getTabs().add(eventsTab);
        }
        if (isPod) {
            detailsTabs.getTabs().add(logsTab);
            detailsTabs.getTabs().add(shellTab);
            detailsTabs.getTabs().add(metricsTab);
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
        runAsync(() -> service.describeYaml(ctx.name(), namespaceBox.getValue(), kind, name),
                yamlArea::setText);

        // Events: the "why" behind a failing resource (skipped for kinds that don't emit events).
        if (kindHasEvents(kind)) {
            eventsArea.setText("Loading…");
            runAsync(() -> service.describeEvents(ctx.name(), namespaceBox.getValue(), kind, name),
                    eventsArea::setText);
        }

        // Logs: load the container list, then the log tail.
        if (isPod) {
            currentLogPod = name;
            shellPod = name;
            setLogStatusDot(columnValue(row, "Status"));
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
            startMetricsPolling(ctx.name(), ns, name);
        } else {
            currentLogPod = null;
            shellPod = null;
            setLogStatusDot(null);
            stopLogStream();
            stopMetricsPolling();
        }
    }

    // --- live pod metrics -------------------------------------------------------------------

    private VBox buildMetricsPane() {
        LineChart<Number, Number> cpuChart = liveChart("CPU (millicores)", cpuSeries);
        LineChart<Number, Number> memChart = liveChart("Memory (MiB)", memSeries);

        Label hint = new Label("Live — samples every 10s while open (needs metrics-server). "
                + "The chart builds up client-side as you watch, since metrics-server keeps no history.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        VBox.setVgrow(cpuChart, Priority.ALWAYS);
        VBox.setVgrow(memChart, Priority.ALWAYS);
        VBox box = new VBox(8, hint, cpuChart, memChart);
        box.setPadding(new Insets(8));
        return box;
    }

    private LineChart<Number, Number> liveChart(String yLabel, XYChart.Series<Number, Number> series) {
        NumberAxis x = new NumberAxis();
        x.setLabel("seconds");
        x.setForceZeroInRange(false);
        NumberAxis y = new NumberAxis();
        y.setLabel(yLabel);
        y.setForceZeroInRange(true);
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.getData().add(series);
        return chart;
    }

    private void startMetricsPolling(String ctxName, String ns, String pod) {
        stopMetricsPolling();
        metricsNs = ns;
        metricsPod = pod;
        cpuSeries.getData().clear();
        memSeries.getData().clear();
        metricsStartMillis = System.currentTimeMillis();
        sampleMetrics(ctxName); // first sample immediately
        metricsTimeline = new Timeline(new KeyFrame(Duration.seconds(10), e -> sampleMetrics(ctxName)));
        metricsTimeline.setCycleCount(Timeline.INDEFINITE);
        metricsTimeline.play();
    }

    private void stopMetricsPolling() {
        if (metricsTimeline != null) {
            metricsTimeline.stop();
            metricsTimeline = null;
        }
    }

    private void sampleMetrics(String ctxName) {
        String ns = metricsNs;
        String pod = metricsPod;
        if (pod == null) {
            return;
        }
        runAsync(() -> service.podUsageSample(ctxName, ns, pod), sample -> {
            if (sample == null || !pod.equals(metricsPod)) {
                return; // no metrics yet, or the details switched to another pod
            }
            double t = (System.currentTimeMillis() - metricsStartMillis) / 1000.0;
            cpuSeries.getData().add(new XYChart.Data<>(t, sample.cpuMillicores()));
            memSeries.getData().add(new XYChart.Data<>(t, sample.memoryMiB()));
            // Keep ~5 minutes of history (30 samples at 10s).
            if (cpuSeries.getData().size() > 30) {
                cpuSeries.getData().remove(0);
            }
            if (memSeries.getData().size() > 30) {
                memSeries.getData().remove(0);
            }
        });
    }

    /** (Re)load the log tail for the currently shown pod + selected container. */
    private void loadLogs() {
        // Any change (container, tail, refresh, follow toggle, new pod) stops the previous stream first.
        stopLogStream();
        if (detailKind != ResourceKind.PODS || currentLogPod == null) {
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
        boolean previous = previousCheck.isSelected();
        boolean timestamps = timestampsCheck.isSelected();

        // Follow streams a live container; a previous (terminated) container can't stream.
        if (followToggle.isSelected() && !previous) {
            startFollow(ctx.name(), ns, pod, container, tail, timestamps);
            return;
        }
        logsArea.setText("Loading logs…");
        runAsync(() -> service.podLogs(ctx.name(), ns, pod, container, tail, previous, timestamps),
                logs -> logBuffer.setText(logs));
    }

    /** Open a live log stream (kubectl logs -f) and append chunks as they arrive, auto-scrolling. */
    private void startFollow(String ctxName, String ns, String pod, String container, int tail,
                             boolean timestamps) {
        logBuffer.clear();
        status("Following logs for " + pod + "… (toggle Follow off to stop)");
        try {
            logStream = service.followLogs(ctxName, ns, pod, container, tail, timestamps,
                    chunk -> Platform.runLater(() -> {
                        // Ignore late chunks if the user switched pods or turned Follow off meanwhile.
                        if (!followToggle.isSelected() || !pod.equals(currentLogPod)) {
                            return;
                        }
                        logBuffer.append(chunk);
                    }),
                    err -> Platform.runLater(() -> {
                        if (pod.equals(currentLogPod) && followToggle.isSelected()) {
                            logBuffer.append("\n[log stream ended"
                                    + (err != null ? ": " + err.getMessage() : "") + "]\n");
                        }
                    }));
        } catch (Exception e) {
            followToggle.setSelected(false);
            statusError("Could not follow logs: " + e.getMessage());
        }
    }

    /** Set the log-toolbar status dot from a pod status/phase string (null/blank hides it). */
    private void setLogStatusDot(String status) {
        currentLogPodStatus = status;
        logStatusDot.getStyleClass().removeAll("dot-ok", "dot-warn", "dot-bad", "dot-neutral");
        if (status == null || status.isBlank()) {
            logStatusDot.setVisible(false);
            return;
        }
        logStatusDot.setVisible(true);
        logStatusDot.getStyleClass().add(chipClassFor(status).replace("chip-", "dot-"));
        logStatusDot.setTooltip(new Tooltip(status));
    }

    /** The value of a named column in a table row (case-insensitive), or null if not present. */
    private String columnValue(List<String> row, String column) {
        if (row == null) {
            return null;
        }
        List<String> cols = currentData.columns();
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).equalsIgnoreCase(column) && i < row.size()) {
                return row.get(i);
            }
        }
        return null;
    }

    /** Stop and free any running live log stream (on pod change, refresh, panel close, or shutdown). */
    private void stopLogStream() {
        if (logStream != null) {
            try {
                logStream.close();
            } catch (Exception ignored) {
                // best-effort
            }
            logStream = null;
        }
    }

    /**
     * Pop the current pod's logs into a standalone window that keeps streaming on its own, sharing
     * this app's {@link ClusterService}. Carries over the current container, tail size and follow state.
     */
    private void detachLogs() {
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null || currentLogPod == null) {
            status("Open a pod's logs first, then detach.");
            return;
        }
        int tail = tailBox.getValue() != null ? tailBox.getValue() : 500;
        new LogWindow(service, ctx.name(), namespaceBox.getValue(), currentLogPod,
                containerBox.getValue(), tail, followToggle.isSelected(), currentLogPodStatus).show();
    }

    /** Save the full (unfiltered) log to a .log file the user can share with colleagues. */
    private void saveLogs() {
        String content = logBuffer.raw();
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
        stopLogStream();
        stopMetricsPolling();
        metricsPod = null;
        centerSplit.getItems().remove(detailsPane);
    }

    // --- mutating actions -------------------------------------------------------------------

    /** Build the details-panel "Actions" menu for the shown resource — same handlers as the table. */
    private void populateDetailsActions(ResourceKind kind, String name) {
        detailsActionsBtn.getItems().clear();

        // RBAC-aware: disable actions the user isn't allowed to perform (mirrors the row menu).
        String g = kind.apiGroup(), r = kind.resource();
        boolean canPatch = currentPermissions.can(g, r, "patch") || currentPermissions.can(g, r, "update");

        if (kind == ResourceKind.DEPLOYMENTS) {
            MenuItem restart = new MenuItem("Restart (rollout)");
            restart.setOnAction(e -> restartAction(name));
            restart.setDisable(!canPatch);
            MenuItem scale = new MenuItem("Scale…");
            scale.setOnAction(e -> scaleAction(name));
            scale.setDisable(!canPatch);
            detailsActionsBtn.getItems().addAll(restart, scale);
        }
        if (kind == ResourceKind.PODS) {
            MenuItem restartPod = new MenuItem("Restart (recreate)");
            restartPod.setOnAction(e -> restartPodAction(name));
            restartPod.setDisable(!currentPermissions.can(g, r, "delete"));
            MenuItem portForward = new MenuItem("Port forward…");
            portForward.setOnAction(e -> openPortForwardDialog(name));
            detailsActionsBtn.getItems().addAll(restartPod, portForward);
        }
        if (!detailsActionsBtn.getItems().isEmpty()) {
            detailsActionsBtn.getItems().add(new SeparatorMenuItem());
        }

        MenuItem copyName = new MenuItem("Copy name");
        copyName.setOnAction(e -> copyToClipboard(name));
        MenuItem copyYaml = new MenuItem("Copy YAML");
        copyYaml.setOnAction(e -> copyYaml(kind, name));
        MenuItem delete = new MenuItem("Delete");
        delete.getStyleClass().add("danger-item");
        delete.setOnAction(e -> deleteAction(kind, name));
        delete.setDisable(!currentPermissions.can(g, r, "delete"));
        detailsActionsBtn.getItems().addAll(copyName, copyYaml, new SeparatorMenuItem(), delete);
    }

    /** Refresh whatever view is showing after a mutation (topology, health, or table). */
    private void afterMutation() {
        switch (currentView) {
            case TOPOLOGY -> loadTopology();
            case HEALTH -> loadHealth();
            default -> refreshContent();
        }
    }

    private void deleteAction(ResourceKind kind, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = namespaceBox.getValue();
        boolean ok = confirm("Delete " + kind.label().toLowerCase() + "?",
                "Delete \"" + name + "\""
                        + (kind.namespaced() ? " in namespace \"" + ns + "\"" : "") + "?\n\n"
                        + "This cannot be undone. (If it's managed by a controller, a replacement "
                        + "may be created automatically.)");
        if (!ok) {
            return;
        }
        status("Deleting " + name + "…");
        runAsyncVoid(() -> service.deleteResource(ctx.name(), ns, kind, name),
                () -> {
                    status("Deleted " + name + ".");
                    if (name.equals(currentDetailName)) {
                        closeDetails();
                    }
                    afterMutation();
                });
    }

    /** The two cleanup choices, used by the health view's "Clean up" menu. */
    private List<MenuItem> buildCleanupMenuItems() {
        MenuItem failed = new MenuItem("Remove evicted / failed pods");
        failed.setOnAction(e -> cleanupPodsAction(java.util.Set.of("Failed"), "evicted / failed"));
        MenuItem all = new MenuItem("Remove failed + completed pods");
        all.setOnAction(e -> cleanupPodsAction(java.util.Set.of("Failed", "Succeeded"), "failed / completed"));
        return List.of(failed, all);
    }

    /** Bulk-delete terminal pods in the current namespace, after confirming the exact count. */
    private void cleanupPodsAction(java.util.Set<String> phases, String label) {
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            status("Select a context first.");
            return;
        }
        String ns = namespaceBox.getValue();
        if (ns == null || ns.isEmpty()) {
            status("Select a namespace first.");
            return;
        }
        status("Scanning for " + label + " pods…");
        // Dry-run count first so the confirmation shows the real number.
        runAsync(() -> service.cleanupPods(ctx.name(), ns, phases, true), count -> {
            if (count == 0) {
                status("No " + label + " pods to clean up in \"" + ns + "\".");
                return;
            }
            String noun = count == 1 ? "pod" : "pods";
            boolean ok = confirm("Clean up " + count + " " + noun + "?",
                    "Delete " + count + " " + label + " " + noun + " in namespace \"" + ns + "\"?\n\n"
                            + "These are terminal pod records — running workloads are unaffected. "
                            + "This cannot be undone.");
            if (!ok) {
                status("Cleanup cancelled.");
                return;
            }
            status("Cleaning up " + count + " " + noun + "…");
            runAsync(() -> service.cleanupPods(ctx.name(), ns, phases, false), deleted -> {
                status("Cleaned up " + deleted + " " + (deleted == 1 ? "pod" : "pods") + ".");
                afterMutation();
            });
        });
    }

    private void restartAction(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
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
                () -> {
                    status("Rolling restart triggered for " + name + ".");
                    afterMutation();
                });
    }

    /**
     * "Restart" a pod: Kubernetes has no restart verb for pods, so this deletes it and relies on its
     * controller (ReplicaSet/StatefulSet/DaemonSet/Job) to create a fresh replacement. The service
     * refuses bare pods, so a mistaken click can't silently lose an unmanaged pod.
     */
    private void restartPodAction(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = namespaceBox.getValue();
        boolean ok = confirm("Restart pod?",
                "Restart \"" + name + "\" in \"" + ns + "\"?\n\n"
                        + "Pods can't be restarted in place: this deletes the pod and its controller "
                        + "creates a fresh replacement (with a new name). Expect a brief blip if this is "
                        + "the only replica. To restart a whole Deployment with no downtime, use "
                        + "Restart (rollout) on the Deployment instead.");
        if (!ok) {
            return;
        }
        status("Restarting " + name + "…");
        runAsync(() -> service.restartPod(ctx.name(), ns, name),
                owner -> {
                    status("Deleted " + name + " — " + owner + " is creating a replacement.");
                    if (name.equals(currentDetailName)) {
                        closeDetails();
                    }
                    afterMutation();
                });
    }

    /** Whether a pod row (from the table) has a controller owner, per its "Controlled By" column. */
    private boolean isControlledPod(List<String> row) {
        int idx = currentData.columns().indexOf("Controlled By");
        if (idx < 0 || row == null || idx >= row.size()) {
            return true; // unknown — let the service make the final call
        }
        String owner = row.get(idx);
        return owner != null && !owner.isBlank() && !"-".equals(owner);
    }

    private void scaleAction(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = namespaceBox.getValue();
        // Fetch the current replica count so the dialog is prefilled accurately, regardless of
        // whether we were invoked from a table row, the details panel, or the topology.
        status("Reading current scale of " + name + "…");
        runAsync(() -> service.getDeploymentReplicas(ctx.name(), ns, name),
                current -> showScaleDialog(ctx, ns, name, current));
    }

    private void showScaleDialog(ContextInfo ctx, String ns, String name, Integer current) {
        TextInputDialog dialog = new TextInputDialog(current != null ? String.valueOf(current) : "");
        dialog.setTitle("Scale deployment");
        dialog.setHeaderText("Scale \"" + name + "\""
                + (current != null ? "  (currently " + current + " replicas)" : ""));
        dialog.setContentText("Desired replicas:");
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
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
                () -> {
                    status("Scaled " + name + " to " + replicas + " replicas.");
                    afterMutation();
                });
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
                    afterMutation();
                });
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

        ContextInfo ctx = contextBox.getValue();
        Button validateBtn = new Button("Validate (dry-run)");
        validateBtn.setTooltip(new Tooltip("Server-side dry-run: check against the cluster's "
                + "schema and admission without applying"));
        validateBtn.setOnAction(e -> {
            String text = editor.getText();
            if (text == null || text.isBlank()) {
                status("Nothing to validate.");
                return;
            }
            status("Validating (server dry-run)…");
            runAsync(() -> service.validateManifests(ctx.name(), namespaceBox.getValue(), text),
                    summary -> showReport("Validation (dry-run)", summary));
        });

        String ns = namespaceBox.getValue();
        Label hint = new Label("Server-side apply · resources without a namespace go to: "
                + (ns != null ? ns : "(context default)"));
        hint.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, openFile, validateBtn, spacer, hint);
        top.setAlignment(Pos.CENTER_LEFT);

        // Live YAML syntax lint under the editor (client-side, debounced as you type).
        Label lintLabel = new Label();
        Runnable doLint = () -> {
            YamlLint.LintResult r = YamlLint.check(editor.getText());
            lintLabel.setText((r.ok() ? "✓ " : "✗ ") + r.message());
            lintLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: "
                    + (r.ok() ? "#15803d" : "#b91c1c") + ";");
        };
        PauseTransition lintPause = new PauseTransition(Duration.millis(350));
        lintPause.setOnFinished(e -> doLint.run());
        editor.textProperty().addListener((o, a, b) -> lintPause.playFromStart());
        doLint.run();

        VBox content = new VBox(8, top, editor, lintLabel);
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

    // --- port forwarding --------------------------------------------------------------------

    private void openPortForwardDialog(String pod) {
        if (pod == null || pod.isEmpty()) {
            return;
        }
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = namespaceBox.getValue();
        status("Reading ports for " + pod + "…");
        runAsync(() -> service.listPodPorts(ctx.name(), ns, pod),
                ports -> showForwardConfig(ctx, ns, pod, ports));
    }

    private void showForwardConfig(ContextInfo ctx, String ns, String pod, List<Integer> ports) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Port forward");
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        ButtonType startType = new ButtonType("Start", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(startType, ButtonType.CANCEL);

        ComboBox<String> remoteBox = new ComboBox<>();
        remoteBox.setEditable(true);
        remoteBox.setPromptText("pod port");
        for (Integer p : ports) {
            remoteBox.getItems().add(String.valueOf(p));
        }
        TextField localField = new TextField();
        localField.setPromptText("local port");
        if (!ports.isEmpty()) {
            remoteBox.setValue(String.valueOf(ports.get(0)));
            localField.setText(String.valueOf(ports.get(0)));
        }

        Label hint = new Label(ports.isEmpty()
                ? "This pod declares no container ports — type the port you want to reach."
                : "Pick or type the pod port, and the local port to bind.");
        hint.getStyleClass().add("muted");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(6, 4, 4, 4));
        grid.add(new Label("Pod:"), 0, 0);
        grid.add(new Label(ns + "/" + pod), 1, 0);
        grid.add(new Label("Pod port:"), 0, 1);
        grid.add(remoteBox, 1, 1);
        grid.add(new Label("Local port:"), 0, 2);
        grid.add(localField, 1, 2);
        dialog.getDialogPane().setContent(new VBox(8, grid, hint));

        Optional<ButtonType> res = dialog.showAndWait();
        if (res.isEmpty() || res.get() != startType) {
            return;
        }
        Integer remote = parsePort(remoteBox.getValue());
        if (remote == null) {
            status("Port-forward cancelled — invalid pod port.");
            return;
        }
        int local = 0;
        if (!localField.getText().isBlank()) {
            Integer lp = parsePort(localField.getText());
            if (lp == null) {
                status("Port-forward cancelled — invalid local port.");
                return;
            }
            local = lp;
        }
        startForward(ctx, ns, pod, remote, local);
    }

    private Integer parsePort(String s) {
        if (s == null) {
            return null;
        }
        try {
            int p = Integer.parseInt(s.trim());
            return (p > 0 && p <= 65535) ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void startForward(ContextInfo ctx, String ns, String pod, int remote, int local) {
        status("Starting port-forward " + pod + " :" + remote + " …");
        runAsync(() -> service.startPortForward(ctx.name(), ns, pod, remote, local), pf -> {
            portForwards.add(pf);
            updatePfButton();
            persistPortForwards();
            showReport("Port-forward started",
                    "Forwarding " + ns + "/" + pod + " :" + pf.remotePort()
                            + "  →  localhost:" + pf.localPort()
                            + "\n\nManage active forwards from the “Port-forwards” button "
                            + "in the toolbar.");
        });
    }

    private void updatePfButton() {
        int n = portForwards.size();
        pfButton.setText("Port-forwards (" + n + ")");
        pfButton.setVisible(n > 0);
        pfButton.setManaged(n > 0);
    }

    private void openForwardsManager() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Active port-forwards");
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        VBox list = new VBox(8);
        list.setPadding(new Insets(6));
        list.setMinWidth(480);
        rebuildForwardsList(list);
        dialog.getDialogPane().setContent(list);
        dialog.showAndWait();
    }

    private void rebuildForwardsList(VBox list) {
        list.getChildren().clear();
        if (portForwards.isEmpty()) {
            list.getChildren().add(new Label("No active port-forwards."));
            return;
        }
        for (PortForward pf : new ArrayList<>(portForwards)) {
            Circle dot = new Circle(5);
            dot.setStyle("-fx-fill: " + (pf.isAlive() ? "#16a34a" : "#dc2626") + ";");

            String address = pf.namespace() + "/" + pf.pod() + "  :" + pf.remotePort()
                    + "  →  localhost:" + pf.localPort();
            Label label = new Label(address);
            label.setTooltip(new Tooltip(address));
            // The long address is the flexible part — let it ellipsize, not the buttons.
            label.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(label, Priority.ALWAYS);

            Button copy = new Button("Copy");
            copy.setTooltip(new Tooltip("Copy localhost:" + pf.localPort() + " to the clipboard"));
            copy.setOnAction(e -> copyToClipboard("localhost:" + pf.localPort()));

            Button open = new Button("Open");
            open.setTooltip(new Tooltip("Open http://localhost:" + pf.localPort() + " in your browser"));
            open.setOnAction(e -> openInBrowser("http://localhost:" + pf.localPort()));

            Button stop = new Button("Stop");
            stop.setTooltip(new Tooltip("Stop this port-forward"));
            stop.setOnAction(e -> {
                closeForward(pf);
                rebuildForwardsList(list);
            });

            // Keep buttons at their natural size so their labels never collapse to "…".
            for (Button b : new Button[]{copy, open, stop}) {
                b.setMinWidth(Region.USE_PREF_SIZE);
            }

            HBox rowBox = new HBox(8, dot, label, copy, open, stop);
            rowBox.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(rowBox);
        }
    }

    private void closeForward(PortForward pf) {
        try {
            pf.close();
        } catch (Exception ignore) {
            // best-effort
        }
        portForwards.remove(pf);
        updatePfButton();
        persistPortForwards();
    }

    private void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                status("Can't open a browser here. URL: " + url);
            }
        } catch (Exception e) {
            status("Could not open browser: " + e.getMessage());
        }
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

    private void copyYaml(ResourceKind kind, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        status("Copying YAML for " + name + "…");
        runAsync(() -> service.describeYaml(ctx.name(), namespaceBox.getValue(), kind, name),
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

    /** A selectable, read-only field that looks like a value label but can be copied. */
    private TextField copyableValue(String text) {
        TextField f = new TextField(text == null ? "" : text);
        f.setEditable(false);
        f.setFocusTraversable(false);
        f.getStyleClass().addAll("kv-value", "flat-field");
        f.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(f, Priority.ALWAYS);
        return f;
    }

    /** ConfigMaps and Secrets don't emit events, so the Events action is noise for them. */
    private boolean kindHasEvents(ResourceKind kind) {
        return kind != ResourceKind.CONFIGMAPS && kind != ResourceKind.SECRETS;
    }

    /** The resource name is always column 0 of our table data. */
    private String resourceName(List<String> row) {
        return row != null && !row.isEmpty() ? row.get(0) : "";
    }

    /** In all-namespaces mode the leading column is Namespace and Name is second. */
    private String nameOfRow(List<String> row) {
        if (allNsMode.get()) {
            return row != null && row.size() > 1 ? row.get(1) : "";
        }
        return resourceName(row);
    }

    /** The namespace a row belongs to: its Namespace column in all-namespaces mode, else the box. */
    private String namespaceOfRow(List<String> row) {
        if (allNsMode.get()) {
            return row != null && !row.isEmpty() ? row.get(0) : effectiveNamespace();
        }
        return effectiveNamespace();
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
            // Prefer the last-used context, else the kube.conf's current-context.
            ContextInfo preferred = null;
            if (settings.lastContext != null) {
                preferred = contexts.stream().filter(c -> c.name().equals(settings.lastContext))
                        .findFirst().orElse(null);
            }
            if (preferred == null) {
                preferred = contexts.stream().filter(ContextInfo::current).findFirst()
                        .orElse(contexts.isEmpty() ? null : contexts.get(0));
            }
            final ContextInfo current = preferred;
            // Set the value with the handler detached so we drive the load exactly once below.
            contextBox.setOnAction(null);
            contextBox.setItems(FXCollections.observableArrayList(contexts));
            contextBox.setValue(current);
            contextBox.setOnAction(e -> onContextChosen());
            refreshClusterRail();
            if (current != null) {
                loadNamespaces(current);
            } else {
                status("No contexts found in kube.conf.");
            }
            restoreForwardsOnce();
        });
    }

    private void loadNamespaces(ContextInfo context) {
        status("Loading namespaces for " + context.name() + "…");
        runAsync(() -> service.listNamespaces(context.name()), namespaces -> {
            String chosen;
            String remembered = settings.namespaceByContext.get(context.name());
            if (remembered != null && !remembered.isBlank()
                    && (namespaces.isEmpty() || namespaces.contains(remembered))) {
                chosen = remembered;
            } else if (context.name().equals(settings.lastContext) && settings.lastNamespace != null
                    && (namespaces.isEmpty() || namespaces.contains(settings.lastNamespace))) {
                chosen = settings.lastNamespace;
            } else if (namespaces.contains(context.namespace())) {
                chosen = context.namespace();
            } else if (namespaces.isEmpty()) {
                // Couldn't enumerate namespaces (e.g. a namespace-scoped token). Trust the namespace
                // from the kubeconfig context so resources still load; the box is editable if you
                // need a different one.
                chosen = context.namespace();
                status("Couldn't list namespaces (token may be namespace-scoped) — using \"" + chosen
                        + "\" from your kube.conf. You can type another namespace in the box.");
            } else if (namespaces.contains("default")) {
                chosen = "default";
            } else {
                chosen = namespaces.get(0);
            }
            // Populate with the handler detached, then trigger a single reload + watch. Offer the
            // "All namespaces" sentinel (first) only when there's more than one namespace to search.
            namespaceBox.setOnAction(null);
            List<String> items = new ArrayList<>();
            if (namespaces.size() > 1) {
                items.add(ALL_NAMESPACES);
            }
            items.addAll(namespaces);
            namespaceBox.setItems(FXCollections.observableArrayList(items));
            namespaceBox.setValue(chosen);
            namespaceBox.setOnAction(e -> onSelectionChanged());
            onSelectionChanged();
        });
    }

    /** The namespace to act on: the box value, or the kubeconfig context's namespace if it's blank. */
    private String effectiveNamespace() {
        String ns = namespaceBox.getValue();
        if (ns != null && !ns.isBlank()) {
            return ns;
        }
        ContextInfo ctx = contextBox.getValue();
        return ctx != null ? ctx.namespace() : null;
    }

    private void refreshContent() {
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        if (allNsMode.get() && currentKind.namespaced()) {
            loadAcrossNamespaces(ctx);
            return;
        }
        String ns = effectiveNamespace();
        if (currentKind.namespaced() && (ns == null || ns.isBlank())) {
            status("Type your namespace in the Namespace box to load "
                    + currentKind.label().toLowerCase() + ".");
            return;
        }
        status("Loading " + currentKind.label() + "…");
        runAsync(() -> service.listResource(ctx.name(), ns, currentKind), data -> {
            currentData = data;
            renderTable(data);
            applyFilter();
            status(data.rows().size() + " " + currentKind.label().toLowerCase() + " loaded.");
            applyPendingSelection();
        });

        if (currentKind == ResourceKind.PODS) {
            runAsync(() -> service.podHealth(ctx.name(), ns), this::renderHealthRing);
        } else {
            healthRing.getChildren().clear();
        }
    }

    /** The namespaces to search in "All namespaces" mode: everything in the dropdown but the sentinel. */
    private List<String> listedNamespaces() {
        List<String> out = new ArrayList<>();
        for (String s : namespaceBox.getItems()) {
            if (!ALL_NAMESPACES.equals(s) && s != null && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private void loadAcrossNamespaces(ContextInfo ctx) {
        List<String> nss = listedNamespaces();
        if (nss.isEmpty()) {
            status("No namespaces to search.");
            return;
        }
        healthRing.getChildren().clear();
        status("Loading " + currentKind.label() + " across " + nss.size() + " namespaces…");
        ResourceKind kind = currentKind;
        runAsync(() -> service.listResourceAcrossNamespaces(ctx.name(), nss, kind), data -> {
            currentData = data;
            renderTable(data);
            applyFilter();
            status(data.rows().size() + " " + kind.label().toLowerCase() + " across "
                    + nss.size() + " namespaces.  (Read-only — pick a namespace to act on one.)");
        });
    }

    /** Read-only YAML viewer for a resource in a specific namespace (used from All-namespaces mode). */
    private void openYamlDialog(ResourceKind kind, String name, String ns) {
        if (name == null || name.isBlank()) {
            return;
        }
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        TextArea yaml = new TextArea("Loading…");
        yaml.setEditable(false);
        yaml.getStyleClass().add("yaml-area");
        yaml.setPrefSize(700, 540);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(kind.label() + ": " + name + "  (" + ns + ")");
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setContent(yaml);
        runAsync(() -> service.describeYaml(ctx.name(), ns, kind, name), yaml::setText);
        dialog.showAndWait();
    }

    // --- live updates (watches) -------------------------------------------------------------

    /** Called when the user changes context/namespace/kind: reload data and (re)start the watch. */
    private void onSelectionChanged() {
        ContextInfo c = contextBox.getValue();
        String ns = namespaceBox.getValue();
        boolean sentinel = ALL_NAMESPACES.equals(ns);
        // All-namespaces behavior only applies to namespaced kinds (Nodes etc. ignore it).
        allNsMode.set(sentinel && currentKind.namespaced());
        settings.lastContext = c != null ? c.name() : null;
        // Don't persist the "All namespaces" sentinel as a real namespace.
        if (!sentinel) {
            settings.lastNamespace = ns;
            if (c != null && ns != null && !ns.isBlank()) {
                settings.namespaceByContext.put(c.name(), ns);
            }
        }
        saveSettings();
        refreshPermissions(c, effectiveNamespace());
        switch (currentView) {
            case TOPOLOGY -> loadTopology();
            case HEALTH -> loadHealth();
            case EVENTS -> loadEvents();
            case OVERVIEW -> loadOverview();
            default -> {
                refreshContent();
                if (paused) {
                    updateLiveUi();
                } else {
                    restartWatch();
                }
            }
        }
    }

    // --- RBAC awareness (proactive) ---------------------------------------------------------

    /** Fetch what the user can do in this namespace (once per context+namespace), then adapt the UI. */
    private void refreshPermissions(ContextInfo ctx, String ns) {
        if (ctx == null || allNsMode.get() || ns == null || ns.isBlank()) {
            currentPermissions = Permissions.unknown(); // show everything (fail open)
            permKey = null;
            updateNavVisibility();
            updateCreateEnabled();
            return;
        }
        String key = ctx.name() + "|" + ns;
        if (key.equals(permKey)) {
            updateNavVisibility();
            return;
        }
        runAsync(() -> service.permissions(ctx.name(), ns), perms -> {
            currentPermissions = perms;
            permKey = key;
            updateNavVisibility();
            updateCreateEnabled();
        });
    }

    /** Hide resource kinds the user can't list; hide a section header when all its kinds are hidden. */
    private void updateNavVisibility() {
        for (var entry : navButtons.entrySet()) {
            ResourceKind kind = entry.getKey();
            ToggleButton btn = entry.getValue();
            boolean visible = !kind.namespaced() // cluster-scoped kinds aren't covered by the namespace review
                    || currentPermissions.can(kind.apiGroup(), kind.resource(), "list");
            btn.setVisible(visible);
            btn.setManaged(visible);
        }
        for (var entry : sectionLabels.entrySet()) {
            boolean any = navButtons.entrySet().stream()
                    .anyMatch(e -> e.getKey().section().equals(entry.getKey()) && e.getValue().isVisible());
            entry.getValue().setVisible(any);
            entry.getValue().setManaged(any);
        }
    }

    private void updateCreateEnabled() {
        boolean canCreate = false;
        for (ResourceKind k : ResourceKind.values()) {
            if (currentPermissions.can(k.apiGroup(), k.resource(), "create")) {
                canCreate = true;
                break;
            }
        }
        if (createBtn != null) {
            createBtn.setDisable(!canCreate);
            createBtn.setTooltip(canCreate ? null
                    : new Tooltip("You don't have permission to create resources in this namespace."));
        }
    }

    // --- view switching (resource table / topology / health) --------------------------------

    /** Switch to a resource-kind table view (also used by topology/health node clicks). */
    private void selectKind(ResourceKind kind) {
        currentKind = kind;
        contentTitle.setText(kind.label());
        ToggleButton btn = navButtons.get(kind);
        if (btn != null) {
            btn.setSelected(true);
        }
        setCenterView(ViewMode.RESOURCE);
        closeDetails();
        onSelectionChanged();
    }

    private void enterTopology() {
        setCenterView(ViewMode.TOPOLOGY);
        topologyToggle.setSelected(true);
        closeDetails();
        stopWatch();
        loadTopology();
    }

    private void enterHealth() {
        setCenterView(ViewMode.HEALTH);
        healthToggle.setSelected(true);
        closeDetails();
        stopWatch();
        loadHealth();
    }

    private void enterOverview() {
        setCenterView(ViewMode.OVERVIEW);
        overviewToggle.setSelected(true);
        closeDetails();
        stopWatch();
        loadOverview();
    }

    private void enterEvents() {
        setCenterView(ViewMode.EVENTS);
        eventsToggle.setSelected(true);
        closeDetails();
        stopWatch();
        loadEvents();
    }

    /** Show exactly one of the resource table (+ring), the topology, health, or events view. */
    private void setCenterView(ViewMode mode) {
        currentView = mode;
        boolean res = mode == ViewMode.RESOURCE;
        table.setVisible(res);
        table.setManaged(res);
        if (!res) {
            healthRing.getChildren().clear();
        }
        healthRing.setVisible(res);
        healthRing.setManaged(res);
        topologyView.setVisible(mode == ViewMode.TOPOLOGY);
        topologyView.setManaged(mode == ViewMode.TOPOLOGY);
        healthView.setVisible(mode == ViewMode.HEALTH);
        healthView.setManaged(mode == ViewMode.HEALTH);
        eventsView.setVisible(mode == ViewMode.EVENTS);
        eventsView.setManaged(mode == ViewMode.EVENTS);
        overviewView.setVisible(mode == ViewMode.OVERVIEW);
        overviewView.setManaged(mode == ViewMode.OVERVIEW);
    }

    private void loadTopology() {
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        if (allNsMode.get()) {
            status("Pick a single namespace to view its topology.");
            return;
        }
        String ns = namespaceBox.getValue();
        contentTitle.setText("Topology · " + safeNamespace());
        status("Building topology…");
        topologyView.resetZoom();
        runAsync(() -> service.getTopology(ctx.name(), ns), data -> {
            topologyView.render(data, this::onTopoNodeClick, this::buildTopoNodeMenu);
            status(data.nodes().size() + " nodes · " + data.edges().size() + " links."
                    + "  (Ctrl+scroll to zoom, drag to pan, click a node to open it.)");
        });
    }

    // --- health view ------------------------------------------------------------------------

    private void buildHealthView() {
        Button rescan = new Button("↻ Rescan");
        rescan.getStyleClass().add("accent-button");
        rescan.setOnAction(e -> loadHealth());

        MenuButton healthCleanup = new MenuButton("🧹 Clean up");
        healthCleanup.getItems().setAll(buildCleanupMenuItems());
        healthCleanup.setTooltip(new Tooltip("Delete terminal (evicted/failed/completed) pods"));

        healthSummary.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(10, healthSummary, spacer, healthCleanup, rescan);
        controls.setAlignment(Pos.CENTER_LEFT);

        healthTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        healthTable.setPlaceholder(new Label("No issues found ✓"));
        VBox.setVgrow(healthTable, Priority.ALWAYS);

        TableColumn<HealthFinding, String> sev = new TableColumn<>("Severity");
        sev.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().severity() == HealthSeverity.CRITICAL ? "Critical" : "Warning"));
        sev.setCellFactory(c -> severityChipCell());
        sev.setPrefWidth(110);
        sev.setMaxWidth(130);

        TableColumn<HealthFinding, String> check = new TableColumn<>("Check");
        check.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().check()));
        check.setPrefWidth(190);
        check.setMaxWidth(240);

        TableColumn<HealthFinding, String> resource = new TableColumn<>("Resource");
        resource.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().kind().label() + "/" + c.getValue().name()));
        resource.setPrefWidth(260);

        TableColumn<HealthFinding, String> detail = new TableColumn<>("Detail");
        detail.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().detail()));

        healthTable.getColumns().setAll(List.of(sev, check, resource, detail));

        // Double-click → open details; right-click → the shared view/act menu.
        healthTable.setRowFactory(tv -> {
            TableRow<HealthFinding> row = new TableRow<>();
            row.itemProperty().addListener((o, a, b) ->
                    row.setContextMenu(b == null ? null : buildResourceMenu(b.kind(), b.name())));
            row.setOnMouseClicked(ev -> {
                if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 2 && !row.isEmpty()) {
                    HealthFinding hf = row.getItem();
                    openDetails(hf.kind(), hf.name(), null, kindHasEvents(hf.kind()) ? eventsTab : yamlTab);
                }
            });
            return row;
        });

        healthView.getChildren().setAll(controls, healthTable);
    }

    private TableCell<HealthFinding, String> severityChipCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("chip", "chip-bad", "chip-warn");
                if (empty || value == null) {
                    setText(null);
                } else {
                    setText(value);
                    getStyleClass().add("chip");
                    getStyleClass().add("Critical".equals(value) ? "chip-bad" : "chip-warn");
                }
            }
        };
    }

    private void loadHealth() {
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        if (allNsMode.get()) {
            status("Pick a single namespace to run the health scan.");
            return;
        }
        String ns = namespaceBox.getValue();
        contentTitle.setText("Health · " + safeNamespace());
        healthSummary.setText("Scanning…");
        status("Running health scan…");
        runAsync(() -> service.healthCheck(ctx.name(), ns), findings -> {
            healthTable.setItems(FXCollections.observableArrayList(findings));
            long crit = findings.stream().filter(h -> h.severity() == HealthSeverity.CRITICAL).count();
            long warn = findings.size() - crit;
            if (findings.isEmpty()) {
                healthSummary.setText("No issues found ✓");
            } else {
                healthSummary.setText(crit + " critical · " + warn + " warning" + (warn == 1 ? "" : "s"));
            }
            status("Health scan complete — " + findings.size() + " finding(s). Double-click to inspect, "
                    + "right-click to act.");
        });
    }

    // --- overview dashboard -----------------------------------------------------------------

    private void buildOverviewView() {
        // Cluster header: color dot + name + environment chip, with the config file beneath.
        ovDot.getStyleClass().add("ov-dot");
        ovName.getStyleClass().add("ov-cluster-name");
        ovEnvChip.getStyleClass().add("ov-env-chip");
        ovEnvChip.setVisible(false);
        ovEnvChip.setManaged(false);
        ovMeta.getStyleClass().add("muted");
        HBox titleRow = new HBox(10, ovDot, ovName, ovEnvChip);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        VBox header = new VBox(2, titleRow, ovMeta);
        header.setPadding(new Insets(0, 0, 4, 0));

        FlowPane tiles = new FlowPane(12, 12,
                tilePods.box, tileWarnings.box, tileDeployments.box, tileNodes.box, tileNamespaces.box);
        tiles.getStyleClass().add("stat-tiles");

        // Tiles double as navigation: jump to the matching resource list or the health view.
        tilePods.onClick(() -> selectKind(ResourceKind.PODS));
        tileDeployments.onClick(() -> selectKind(ResourceKind.DEPLOYMENTS));
        tileNodes.onClick(() -> selectKind(ResourceKind.NODES));
        tileWarnings.onClick(this::enterHealth);

        Label capacityHeader = new Label("Capacity");
        capacityHeader.getStyleClass().add("overview-section");
        Label nodesHeader = new Label("Nodes");
        nodesHeader.getStyleClass().add("overview-section");
        Label unhealthyHeader = new Label("Unhealthy pods");
        unhealthyHeader.getStyleClass().add("overview-section");
        Label eventsHeader = new Label("Warning events");
        eventsHeader.getStyleClass().add("overview-section");

        // Capacity and Nodes share one row, each taking half the width (prefWidth 0 + equal Hgrow
        // makes them split the viewport evenly regardless of their content width).
        VBox capacityCol = new VBox(10, capacityHeader, overviewCapacityBox);
        VBox nodesCol = new VBox(10, nodesHeader, overviewNodesBox);
        capacityCol.setPrefWidth(0);
        nodesCol.setPrefWidth(0);
        capacityCol.setMaxWidth(Double.MAX_VALUE);
        nodesCol.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(capacityCol, Priority.ALWAYS);
        HBox.setHgrow(nodesCol, Priority.ALWAYS);
        HBox topRow = new HBox(24, capacityCol, nodesCol);

        VBox lists = new VBox(14, topRow, unhealthyHeader, overviewUnhealthyBox, eventsHeader, overviewEventsBox);
        ScrollPane scroll = new ScrollPane(lists);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("overview-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        overviewView.getChildren().setAll(header, tiles, scroll);
    }

    private void loadOverview() {
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        if (allNsMode.get()) {
            status("Pick a single namespace for the overview.");
            return;
        }
        String ns = namespaceBox.getValue();
        contentTitle.setText("Overview · " + safeNamespace());

        // Cluster-identity header (color, name, environment, config file).
        String cn = ctx.name();
        ovName.setText(cn);
        ovDot.setStyle("-fx-text-fill: " + clusterColorFor(cn) + ";");
        String env = effectiveEnvironment(cn);
        boolean hasEnv = env != null && !env.isBlank();
        ovEnvChip.setText(hasEnv ? env : "");
        ovEnvChip.setVisible(hasEnv);
        ovEnvChip.setManaged(hasEnv);
        if (hasEnv) {
            String ec = environmentColor(env);
            ovEnvChip.setStyle("-fx-background-color: " + (ec != null ? ec : clusterColorFor(cn)) + ";");
        }
        File src = clusterFiles.get(cn);
        String fileText = (src == null || sameFile(src, defaultKubeConfigFile()))
                ? "default ~/.kube/config" : src.getAbsolutePath();
        ovMeta.setText("namespace: " + safeNamespace() + "    ·    " + fileText);

        final int gen = ++overviewGen;
        tilePods.set("…", "");
        tileWarnings.set("…", "");
        tileDeployments.set("…", "");
        tileNodes.set("…", "");
        tileNamespaces.set("…", "");
        overviewCapacityBox.getChildren().setAll(mutedLabel("Loading…"));
        overviewNodesBox.getChildren().setAll(mutedLabel("Loading…"));
        overviewUnhealthyBox.getChildren().setAll(mutedLabel("Loading…"));
        overviewEventsBox.getChildren().setAll(mutedLabel("Loading…"));
        status("Loading overview…");

        // Pods tile from the pod-phase summary.
        runAsync(() -> service.podHealth(ctx.name(), ns), hs -> {
            if (gen != overviewGen) {
                return;
            }
            int total = hs.total();
            int running = hs.phaseCounts().getOrDefault("Running", 0);
            int pending = hs.phaseCounts().getOrDefault("Pending", 0);
            int failed = hs.phaseCounts().getOrDefault("Failed", 0)
                    + hs.phaseCounts().getOrDefault("Unknown", 0);
            tilePods.set(running + "/" + total,
                    failed > 0 ? failed + " failing" : (pending > 0 ? pending + " pending" : "all running"));
        });

        // Deployments tile: how many are fully available.
        runAsync(() -> service.listResource(ctx.name(), ns, ResourceKind.DEPLOYMENTS), rd -> {
            if (gen != overviewGen) {
                return;
            }
            int total = rd.rows().size();
            int avail = 0;
            for (List<String> row : rd.rows()) {
                String[] parts = cell(row, rd.columns(), "Ready").split("/");
                if (parts.length == 2 && parts[0].equals(parts[1]) && !"0".equals(parts[1])) {
                    avail++;
                }
            }
            tileDeployments.set(avail + "/" + total, total == 0 ? "none" : (total - avail) + " not ready");
        });

        // Nodes tile (cluster-wide; the namespace is ignored for this kind).
        runAsync(() -> service.listResource(ctx.name(), ns, ResourceKind.NODES), rd -> {
            if (gen != overviewGen) {
                return;
            }
            int total = rd.rows().size();
            long ready = rd.rows().stream()
                    .filter(r -> "Ready".equalsIgnoreCase(cell(r, rd.columns(), "Status"))).count();
            tileNodes.set(ready + "/" + total, ready == total ? "all ready" : (total - ready) + " not ready");
        });

        // Namespaces tile (cluster-wide count).
        runAsync(() -> service.listNamespaces(ctx.name()), names -> {
            if (gen != overviewGen) {
                return;
            }
            tileNamespaces.set(String.valueOf(names.size()), "in the cluster");
        });

        // Cluster capacity: requested/used vs allocatable for CPU and memory, as labeled meters.
        runAsync(() -> service.clusterCapacity(ctx.name()), cap -> {
            if (gen != overviewGen) {
                return;
            }
            buildCapacityMeters(cap);
        });

        // Per-node usage table (name · status · CPU% · Mem%), from the enriched Nodes listing.
        runAsync(() -> service.listResource(ctx.name(), ns, ResourceKind.NODES), rd -> {
            if (gen != overviewGen) {
                return;
            }
            if (rd.rows().isEmpty()) {
                overviewNodesBox.getChildren().setAll(mutedLabel("No nodes visible."));
            } else {
                overviewNodesBox.getChildren().setAll(
                        rd.rows().stream().map(r -> overviewNodeRow(r, rd.columns())).toList());
            }
        });

        // Health findings → Warnings tile + the unhealthy-pods list.
        runAsync(() -> service.healthCheck(ctx.name(), ns), findings -> {
            if (gen != overviewGen) {
                return;
            }
            long crit = findings.stream().filter(h -> h.severity() == HealthSeverity.CRITICAL).count();
            tileWarnings.set(String.valueOf(findings.size()), crit + " critical");
            List<HealthFinding> pods = findings.stream()
                    .filter(h -> h.kind() == ResourceKind.PODS).limit(12).toList();
            if (pods.isEmpty()) {
                overviewUnhealthyBox.getChildren().setAll(mutedLabel("No unhealthy pods ✓"));
            } else {
                overviewUnhealthyBox.getChildren().setAll(
                        pods.stream().map(this::overviewUnhealthyRow).toList());
            }
        });

        // Warning events (most-recent first, capped).
        runAsync(() -> service.listEvents(ctx.name(), ns), rd -> {
            if (gen != overviewGen) {
                return;
            }
            List<Node> lines = new ArrayList<>();
            for (List<String> row : rd.rows()) {
                if ("Warning".equalsIgnoreCase(cell(row, rd.columns(), "Type"))) {
                    lines.add(overviewEventLine(row, rd.columns()));
                    if (lines.size() >= 12) {
                        break;
                    }
                }
            }
            overviewEventsBox.getChildren().setAll(
                    lines.isEmpty() ? List.of(mutedLabel("No warning events ✓")) : lines);
            status("Overview ready.");
        });
    }

    /** Render the Capacity section: CPU/memory requested and used, each as a labeled meter. */
    private void buildCapacityMeters(ClusterCapacity cap) {
        double cpuAlloc = cap.cpuAllocatableMillicores();
        double memAlloc = cap.memAllocatableMiB();
        if (cpuAlloc <= 0 && memAlloc <= 0) {
            overviewCapacityBox.getChildren().setAll(mutedLabel("No node capacity data."));
            return;
        }
        List<Node> meters = new ArrayList<>();
        meters.add(capacityMeter("CPU requested",
                fmtCores(cap.cpuRequestedMillicores()) + " / " + fmtCores(cpuAlloc)
                        + "  (" + pct(cap.cpuRequestedMillicores(), cpuAlloc) + "%)",
                frac(cap.cpuRequestedMillicores(), cpuAlloc)));
        meters.add(capacityMeter("Memory requested",
                fmtGi(cap.memRequestedMiB()) + " / " + fmtGi(memAlloc)
                        + "  (" + pct(cap.memRequestedMiB(), memAlloc) + "%)",
                frac(cap.memRequestedMiB(), memAlloc)));
        if (cap.metricsAvailable()) {
            meters.add(capacityMeter("CPU used",
                    fmtCores(cap.cpuUsedMillicores()) + " / " + fmtCores(cpuAlloc)
                            + "  (" + pct(cap.cpuUsedMillicores(), cpuAlloc) + "%)",
                    frac(cap.cpuUsedMillicores(), cpuAlloc)));
            meters.add(capacityMeter("Memory used",
                    fmtGi(cap.memUsedMiB()) + " / " + fmtGi(memAlloc)
                            + "  (" + pct(cap.memUsedMiB(), memAlloc) + "%)",
                    frac(cap.memUsedMiB(), memAlloc)));
        } else {
            meters.add(mutedLabel("Live usage needs metrics-server (kubectl top) — not installed."));
        }
        overviewCapacityBox.getChildren().setAll(meters);
    }

    /** One capacity row: a label, a right-aligned value, and a fill bar beneath (colored when hot). */
    private Node capacityMeter(String label, String valueText, double fraction) {
        Label name = new Label(label);
        name.getStyleClass().add("capacity-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label value = new Label(valueText);
        value.getStyleClass().add("capacity-value");
        value.setStyle("-fx-text-fill: " + utilColor(fraction) + ";");
        HBox top = new HBox(8, name, spacer, value);
        top.setAlignment(Pos.CENTER_LEFT);

        ProgressBar bar = new ProgressBar(Math.max(0, Math.min(1, fraction)));
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("capacity-bar");
        if (fraction >= 0.9) {
            bar.getStyleClass().add("capacity-bar-hot");
        } else if (fraction >= 0.75) {
            bar.getStyleClass().add("capacity-bar-warn");
        }
        return new VBox(4, top, bar);
    }

    /** One Overview node row: name · status · CPU% · Mem%, click-through to the node's details. */
    private Node overviewNodeRow(List<String> row, List<String> cols) {
        String nodeName = cell(row, cols, "Name");
        String statusText = cell(row, cols, "Status");
        Label name = new Label(nodeName);
        name.getStyleClass().add("overview-row-title");
        HBox.setHgrow(name, Priority.ALWAYS);
        name.setMaxWidth(Double.MAX_VALUE);
        Label status = new Label(statusText.isBlank() ? "-" : statusText);
        status.setMinWidth(70);
        status.setStyle("-fx-text-fill: " + ("Ready".equalsIgnoreCase(statusText) ? "#2e7d32" : "#d32f2f") + ";");
        Label cpu = utilizationLabel("CPU ", cell(row, cols, "CPU"));
        cpu.setMinWidth(72);
        Label mem = utilizationLabel("Mem ", cell(row, cols, "Mem"));
        mem.setMinWidth(72);
        HBox box = new HBox(12, name, status, cpu, mem);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("overview-row");
        box.setOnMouseClicked(e -> openDetails(ResourceKind.NODES, nodeName, null,
                kindHasEvents(ResourceKind.NODES) ? eventsTab : yamlTab));
        return box;
    }

    private static String dashIfBlank(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /**
     * A "{prefix}{pct}" label colored by utilization (green/orange/red). When the percent can't be
     * parsed (metrics unavailable → "-"), it stays muted gray.
     */
    private Label utilizationLabel(String prefix, String pctText) {
        Label label = new Label(prefix + dashIfBlank(pctText));
        Integer p = parsePercent(pctText);
        if (p == null) {
            label.getStyleClass().add("muted");
        } else {
            label.setStyle("-fx-text-fill: " + utilColor(p / 100.0) + ";");
        }
        return label;
    }

    /** Traffic-light color for a 0–1 utilization: green &lt;75%, orange 75–90%, red ≥90%. */
    private static String utilColor(double fraction) {
        if (fraction >= 0.90) {
            return "#d32f2f";
        }
        if (fraction >= 0.75) {
            return "#ed6c02";
        }
        return "#2e7d32";
    }

    /** Parse "18%" → 18; null if there's no number (e.g. "-"). */
    private static Integer parsePercent(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Millicores → a compact cores string: whole numbers plain ("24"), else two decimals ("2.10"). */
    private static String fmtCores(double millicores) {
        double cores = millicores / 1000.0;
        return cores == Math.rint(cores) ? String.format("%.0f", cores) : String.format("%.2f", cores);
    }

    /** MiB → a one-decimal GiB string with the Kubernetes "Gi" suffix ("4.4Gi"). */
    private static String fmtGi(double mib) {
        return String.format("%.1f", mib / 1024.0) + "Gi";
    }

    /** Whole-number percentage of part/whole, guarding divide-by-zero. */
    private static long pct(double part, double whole) {
        return whole <= 0 ? 0 : Math.round(part / whole * 100.0);
    }

    /** Fraction (0–1) of part/whole for a progress bar, guarding divide-by-zero. */
    private static double frac(double part, double whole) {
        return whole <= 0 ? 0 : part / whole;
    }

    private Label mutedLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        return l;
    }

    /** A clickable unhealthy-pod row (opens the pod's details on click). */
    private Node overviewUnhealthyRow(HealthFinding f) {
        Label name = new Label(f.name());
        name.getStyleClass().add("overview-row-title");
        Label detail = new Label(f.check() + " — " + f.detail());
        detail.getStyleClass().add("muted");
        detail.setWrapText(true);
        VBox box = new VBox(1, name, detail);
        box.getStyleClass().addAll("overview-row", "overview-row-danger");
        // Deep-link into the resource list for this kind, with the row selected and its details open.
        box.setOnMouseClicked(e -> openInResourceView(f.kind(), f.name()));
        return box;
    }

    /**
     * Switch to the resource list for {@code kind}, then (once it loads) select the named row and open
     * its details panel — used to deep-link from the Overview's unhealthy-pods list.
     */
    private void openInResourceView(ResourceKind kind, String name) {
        pendingSelectName = name;
        selectKind(kind); // sets the view + triggers an async table load; applyPendingSelection runs after
    }

    /** After a resource-table load, honor a pending deep-link: select the row and reveal its details. */
    private void applyPendingSelection() {
        if (pendingSelectName == null) {
            return;
        }
        String want = pendingSelectName;
        pendingSelectName = null;
        for (List<String> row : currentData.rows()) {
            if (want.equals(nameOfRow(row))) {
                table.getSelectionModel().select(row);
                table.scrollTo(row);
                showDetails(row, kindHasEvents(currentKind) ? eventsTab : yamlTab);
                return;
            }
        }
        // Not in the table (e.g. filtered out or already gone) — still open details by name.
        openDetails(currentKind, want, null, kindHasEvents(currentKind) ? eventsTab : yamlTab);
    }

    private Node overviewEventLine(List<String> row, List<String> cols) {
        Label head = new Label(cell(row, cols, "Reason") + "   " + cell(row, cols, "Object"));
        head.getStyleClass().add("overview-row-title");
        Label body = new Label(cell(row, cols, "Message"));
        body.getStyleClass().add("muted");
        body.setWrapText(true);
        VBox box = new VBox(1, head, body);
        box.getStyleClass().addAll("overview-row", "overview-row-warn");
        // Deep-link to the event's object (Pod/Deployment/…), like the unhealthy-pods list. When the
        // object isn't a kind KubeDesk browses, fall back to the full Events view.
        String object = cell(row, cols, "Object"); // "Kind/name"
        int slash = object.indexOf('/');
        String objName = slash >= 0 ? object.substring(slash + 1) : object;
        ResourceKind rk = slash > 0 ? resourceKindForEventKind(object.substring(0, slash)) : null;
        if (rk != null && !objName.isBlank()) {
            box.setOnMouseClicked(e -> openInResourceView(rk, objName));
        } else {
            box.setOnMouseClicked(e -> enterEvents());
        }
        return box;
    }

    /** Map an event involvedObject Kind ("Pod", "Deployment", …) to a browsable kind; null if none. */
    private static ResourceKind resourceKindForEventKind(String kind) {
        return switch (kind) {
            case "Pod" -> ResourceKind.PODS;
            case "Deployment" -> ResourceKind.DEPLOYMENTS;
            case "StatefulSet" -> ResourceKind.STATEFULSETS;
            case "DaemonSet" -> ResourceKind.DAEMONSETS;
            case "Job" -> ResourceKind.JOBS;
            case "CronJob" -> ResourceKind.CRONJOBS;
            case "Service" -> ResourceKind.SERVICES;
            case "Ingress" -> ResourceKind.INGRESSES;
            case "ConfigMap" -> ResourceKind.CONFIGMAPS;
            case "Secret" -> ResourceKind.SECRETS;
            case "PersistentVolumeClaim" -> ResourceKind.PVCS;
            case "Node" -> ResourceKind.NODES;
            default -> null;
        };
    }

    /** The value of a named column in a (columns, row) pair, case-insensitive; "" if absent. */
    private static String cell(List<String> row, List<String> cols, String name) {
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).equalsIgnoreCase(name) && i < row.size()) {
                return row.get(i);
            }
        }
        return "";
    }

    // --- events view ------------------------------------------------------------------------

    private void buildEventsView() {
        Button refresh = new Button("↻ Refresh");
        refresh.getStyleClass().add("accent-button");
        refresh.setOnAction(e -> loadEvents());

        eventsSummary.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(10, eventsSummary, spacer, refresh);
        controls.setAlignment(Pos.CENTER_LEFT);

        nsEventsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        nsEventsTable.setPlaceholder(new Label("No events (Kubernetes keeps them for only ~1 hour)."));
        VBox.setVgrow(nsEventsTable, Priority.ALWAYS);

        // Columns map to the listEvents row order: Type, Reason, Object, Count, Last Seen, Message.
        String[] headers = {"Type", "Reason", "Object", "Count", "Last Seen", "Message"};
        double[] widths = {90, 200, 230, 70, 110, 400};
        for (int i = 0; i < headers.length; i++) {
            final int idx = i;
            TableColumn<List<String>, String> col = new TableColumn<>(headers[i]);
            col.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                    c.getValue() != null && idx < c.getValue().size() ? c.getValue().get(idx) : ""));
            col.setPrefWidth(widths[i]);
            nsEventsTable.getColumns().add(col);
        }

        nsEventsTable.setRowFactory(tv -> {
            TableRow<List<String>> row = new TableRow<>();
            row.itemProperty().addListener((o, a, b) -> {
                row.getStyleClass().remove("event-warning");
                if (b != null && !b.isEmpty() && "Warning".equals(b.get(0))) {
                    row.getStyleClass().add("event-warning");
                }
            });
            MenuItem copyMsg = new MenuItem("Copy message");
            copyMsg.setOnAction(e -> {
                List<String> it = row.getItem();
                if (it != null && it.size() > 5) {
                    copyToClipboard(it.get(5));
                }
            });
            row.setContextMenu(new ContextMenu(copyMsg));
            return row;
        });

        eventsView.getChildren().setAll(controls, nsEventsTable);
    }

    private void loadEvents() {
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = namespaceBox.getValue();
        if (ALL_NAMESPACES.equals(ns)) {
            status("Pick a single namespace to view its events.");
            return;
        }
        if (ns == null || ns.isBlank()) {
            ns = effectiveNamespace();
        }
        if (ns == null || ns.isBlank()) {
            status("No namespace selected.");
            return;
        }
        contentTitle.setText("Events · " + ns);
        eventsSummary.setText("Loading…");
        status("Loading events…");
        final String namespace = ns;
        runAsync(() -> service.listEvents(ctx.name(), namespace), data -> {
            nsEventsTable.setItems(FXCollections.observableArrayList(data.rows()));
            long warns = data.rows().stream()
                    .filter(r -> !r.isEmpty() && "Warning".equals(r.get(0))).count();
            eventsSummary.setText(data.rows().size() + " events · " + warns
                    + " warning" + (warns == 1 ? "" : "s"));
            status(data.rows().size() + " events in " + namespace + ".");
        });
    }

    /** Clicking a topology node opens its details in place (the topology view stays visible). */
    private void onTopoNodeClick(TopoNode node) {
        ResourceKind kind = kindOf(node.kind());
        if (kind != null) {
            showDetailsFor(kind, node.name());
        }
    }

    private ResourceKind kindOf(String topoKind) {
        return switch (topoKind) {
            case "Ingress" -> ResourceKind.INGRESSES;
            case "Service" -> ResourceKind.SERVICES;
            case "Deployment" -> ResourceKind.DEPLOYMENTS;
            case "StatefulSet" -> ResourceKind.STATEFULSETS;
            case "DaemonSet" -> ResourceKind.DAEMONSETS;
            case "Pod" -> ResourceKind.PODS;
            default -> null;
        };
    }

    /** Right-click menu for a topology node — reuses the shared (kind, name) menu builder. */
    private ContextMenu buildTopoNodeMenu(TopoNode node) {
        ResourceKind kind = kindOf(node.kind());
        if (kind == null) {
            return null;
        }
        return buildResourceMenu(kind, node.name());
    }

    /** A view/act context menu for any resource, keyed by (kind, name) — shared everywhere. */
    private ContextMenu buildResourceMenu(ResourceKind kind, String name) {
        ContextMenu menu = new ContextMenu();

        MenuItem details = new MenuItem("View details");
        details.setOnAction(e -> openDetails(kind, name, null, overviewTab));
        MenuItem yaml = new MenuItem("View YAML");
        yaml.setOnAction(e -> openDetails(kind, name, null, yamlTab));
        menu.getItems().addAll(details, yaml);
        if (kindHasEvents(kind)) {
            MenuItem events = new MenuItem("View events");
            events.setOnAction(e -> openDetails(kind, name, null, eventsTab));
            menu.getItems().add(events);
        }
        if (kind == ResourceKind.PODS) {
            MenuItem logs = new MenuItem("View logs");
            logs.setOnAction(e -> openDetails(kind, name, null, logsTab));
            menu.getItems().add(logs);
        }
        menu.getItems().add(new SeparatorMenuItem());

        if (kind == ResourceKind.DEPLOYMENTS) {
            MenuItem restart = new MenuItem("Restart (rollout)");
            restart.setOnAction(e -> restartAction(name));
            MenuItem scale = new MenuItem("Scale…");
            scale.setOnAction(e -> scaleAction(name));
            menu.getItems().addAll(restart, scale);
        }
        if (kind == ResourceKind.PODS) {
            MenuItem restartPod = new MenuItem("Restart (recreate)");
            restartPod.setOnAction(e -> restartPodAction(name));
            MenuItem portForward = new MenuItem("Port forward…");
            portForward.setOnAction(e -> openPortForwardDialog(name));
            menu.getItems().addAll(restartPod, portForward);
        }

        MenuItem copyName = new MenuItem("Copy name");
        copyName.setOnAction(e -> copyToClipboard(name));
        MenuItem copyYaml = new MenuItem("Copy YAML");
        copyYaml.setOnAction(e -> copyYaml(kind, name));
        MenuItem delete = new MenuItem("Delete");
        delete.getStyleClass().add("danger-item");
        delete.setOnAction(e -> deleteAction(kind, name));
        menu.getItems().addAll(copyName, copyYaml, new SeparatorMenuItem(), delete);
        return menu;
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
        if (allNsMode.get()) {
            return; // no live watch in all-namespaces mode
        }
        ContextInfo ctx = contextBox.getValue();
        if (ctx == null) {
            return;
        }
        String ns = effectiveNamespace();
        if (currentKind.namespaced() && (ns == null || ns.isBlank())) {
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
        stopLogStream();
        stopMetricsPolling();
        if (console != null) {
            console.shutdown();
        }
        for (PortForward pf : new ArrayList<>(portForwards)) {
            try {
                pf.close();
            } catch (Exception ignore) {
                // best-effort
            }
        }
        portForwards.clear();
        stopWatch();
        if (mcpServer != null) {
            mcpServer.stop();
        }
        pool.shutdownNow();
    }

    // --- rendering --------------------------------------------------------------------------

    private void renderTable(ResourceData data) {
        table.getColumns().clear();
        // In all-namespaces mode the columns shift (Namespace prepended); don't apply per-kind hiding.
        Set<String> hidden = allNsMode.get()
                ? Set.of()
                : hiddenColumns.computeIfAbsent(currentKind, k -> new HashSet<>());
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
                persistHiddenColumns();
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
            case "Node", "Hosts", "Volume", "Controlled By" -> 200;
            case "Ports" -> 170;
            case "Address", "Schedule" -> 150;
            case "Roles", "Version", "Cluster-IP", "StorageClass", "Last Schedule", "Access Modes" -> 140;
            case "Status", "Type", "Class", "IP" -> 120;
            case "Up-to-date", "Completions", "QoS" -> 100;
            case "Available", "Restarts", "Ready", "Desired", "Active", "Capacity", "Memory" -> 90;
            case "Suspend", "Data", "CPU", "Mem" -> 80;
            case "Age" -> 70;
            default -> 130;
        };
    }

    /** Floor width so narrow columns never collapse and "Name" never gets crushed. */
    private double columnMinWidth(String name) {
        return switch (name) {
            case "Name" -> 160;
            case "Image" -> 140;
            case "Node", "Ports", "Cluster-IP", "Roles", "Version", "Hosts", "Address",
                 "Controlled By", "IP" -> 100;
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
            case "IP" -> 170;
            case "QoS" -> 130;
            case "Ready", "Up-to-date", "Available", "Restarts", "CPU", "Memory", "Mem" -> 110;
            case "Age" -> 90;
            default -> Double.MAX_VALUE;
        };
    }

    /** Short, fixed-format columns read better centered; free-text columns stay left-aligned. */
    private boolean isCenteredColumn(String name) {
        return switch (name) {
            case "Ready", "Up-to-date", "Available", "Restarts", "Age", "Type", "Version",
                 "Desired", "Completions", "Active", "Suspend", "Data", "Capacity", "Last Schedule",
                 "CPU", "Memory", "Mem" -> true;
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
            statusError(withForbiddenHint(ex != null ? ex.getMessage() : "unknown"));
        });
        pool.submit(task);
    }

    /** If a message looks like an RBAC denial, add a hint to set the namespace. */
    private String withForbiddenHint(String message) {
        String base = "Error: " + message;
        if (message != null && message.toLowerCase().contains("forbidden")) {
            return base + "  — if your token is namespace-scoped, type your namespace "
                    + "in the Namespace box and press Enter.";
        }
        return base;
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
            statusError("Error: " + (ex != null ? ex.getMessage() : "unknown"));
        });
        pool.submit(task);
    }

    private void status(String message) {
        setStatus(message, false);
    }

    /** Show an error in the status bar, rendered prominently (red, bold) so it isn't missed. */
    private void statusError(String message) {
        setStatus(message, true);
    }

    private void setStatus(String message, boolean error) {
        Runnable apply = () -> {
            statusBar.setText(message);
            statusBar.pseudoClassStateChanged(ERROR_PSEUDO, error);
            // Errors would otherwise sit red in the bar forever; auto-reset to "Ready." after a pause.
            // Any newer status (incl. one from navigating to another view) restarts/cancels this.
            statusErrorClear.stop();
            if (error) {
                statusErrorClear.playFromStart();
            }
        };
        if (Platform.isFxApplicationThread()) {
            apply.run();
        } else {
            Platform.runLater(apply);
        }
    }

    /** A dashboard stat tile: a colored icon badge, a title, a big value, and a muted subtitle. */
    private static final class StatTile {
        final HBox box = new HBox(12);
        final Label value = new Label("—");
        final Label subtitle = new Label();

        StatTile(String title, String icon, String badgeClass) {
            Label badge = new Label(icon);
            badge.getStyleClass().addAll("stat-badge", badgeClass);

            Label titleLabel = new Label(title);
            titleLabel.getStyleClass().add("stat-title");
            value.getStyleClass().add("stat-value");
            subtitle.getStyleClass().add("stat-subtitle");

            VBox text = new VBox(1, titleLabel, value, subtitle);
            box.getChildren().setAll(badge, text);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("stat-tile");
        }

        void set(String v, String sub) {
            value.setText(v);
            subtitle.setText(sub == null ? "" : sub);
        }

        /** Make the tile act as a button that navigates somewhere (adds a hand cursor + hover). */
        void onClick(Runnable action) {
            box.getStyleClass().add("stat-clickable");
            box.setOnMouseClicked(e -> action.run());
        }
    }

    private static ThreadFactory daemonThreads() {
        return r -> {
            Thread t = new Thread(r, "kubedesk-worker");
            t.setDaemon(true);
            return t;
        };
    }
}
