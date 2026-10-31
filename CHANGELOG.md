# Changelog

All notable changes to KubeDesk are documented here.
This project adheres to [Semantic Versioning](https://semver.org/).

## [1.13.2] - 2026-10-31

A fix release, with the MCP diagnostic changes made alongside it.

### Fixed
- **Quieted benign fabric8 watch log noise.** Closing a client with an active watch (context switch, or a
  reconnect during an API-server blip) could log a `RejectedExecutionException` at ERROR from fabric8's
  own thread. A new `simplelogger.properties` silences the watch managers responsible; re-enable them
  when debugging watch/reconnect issues.
- **MCP `health_scan` all-namespaces no longer hangs on one slow namespace.** Each namespace gets its own
  timeout and the scan honors an overall deadline, so a forbidden/unreachable/slow namespace is recorded
  in `namespacesFailed` and the rest still return as partial results.

### Added
- **MCP: whole-cluster health scan.** `health_scan` gains `allNamespaces: true` — one call scans every
  visible namespace, each finding tagged with its namespace.
- **MCP: `cluster_overview` tool.** The Overview dashboard's capacity — node count and CPU/memory as
  requested · used · allocatable with a utilization % — returned as data for an agent.
- **MCP: `events` tool.** Recent namespace Events (FailedScheduling, OOMKilled, probe failures,
  back-off, evictions), newest first, with a `nameContains` filter — the primary "why" signal.
- **MCP: `pod_logs` gains `previous`, `timestamps`, and `grep`.** `previous` returns the prior crashed
  container instance (the key to a CrashLoopBackOff); `grep` filters lines server-side.

### Changed
- **MCP: richer "High restarts" finding.** Now reports the restart count plus the pod's age, the average
  rate, and the previous instance's exit code/reason and when — and rises to CRITICAL for a frequent,
  recent crashloop while occasional restarts stay a warning (so "16 in 2h" reads differently from
  "16 over 51d").
- **MCP: `describe_yaml` strips `metadata.managedFields`** from its output (the UI YAML tab is
  unchanged) — large, non-actionable server-side-apply bookkeeping an agent never needs.

## [1.13.1] - 2026-10-31

### Changed
- **Toolbar: search + actions pinned right.** The top bar now keeps cluster identity and the
  Context/Namespace selectors on the left (still wrapping on a narrow window) and right-aligns Search,
  refresh, the live toggle, port-forwards, and the ☰ menu.
- **Overview lists are clickable deep-links.** Clicking an **Unhealthy pod** — or a **Warning event**'s
  object — jumps to that resource's list with the row selected and its details open (Events tab). A
  warning event whose object isn't a browsable kind falls back to the full Events view.
- **Overview row accents.** Each Overview row gets a colored left bar so the lists are distinct at a
  glance: red for unhealthy pods, amber for warning events, neutral for nodes.

### Fixed
- Warning-event rows showed a clickable (hand) cursor but did nothing — they now deep-link.

## [1.13.0] - 2026-10-31

### Added
- **Cluster capacity on the Overview.** A new **Capacity** panel shows CPU and memory as *requested*
  and *used* against the cluster's total allocatable — each a labeled meter (`2.10 / 24  (9%)`) with a
  fill bar. Requests come from the running pods' container specs; live usage comes from the Metrics API
  (metrics-server), with a clear note when it isn't installed (requested still works without it).
- **Per-node usage on the Overview.** A **Nodes** panel beside Capacity lists each node with its status
  and live **CPU% / Mem%** of allocatable; click a row to open the node's details. The full **Nodes**
  resource list also gains **CPU** and **Mem** columns.
- **Traffic-light utilization.** Capacity bars/values and node CPU/Mem percentages are colored by load —
  **green** under 75%, **orange** 75–90%, **red** at 90%+ — a consistent at-a-glance health cue.

### Changed
- **Overview layout.** Capacity and Nodes sit side by side (half width each) above the Unhealthy-pods
  and Warning-events lists.
- **Registering/switching a cluster lands on its Overview.** Loading a kube.conf, or hitting **Switch**
  in the cluster editor, now opens the Overview and populates it — previously it could stay on the old
  view, so the summary (and its unhealthy-pods list) didn't appear until you clicked Overview yourself.

## [1.12.0] - 2026-10-31

### Added
- **Cluster rail + multi-kubeconfig.** A colored avatar per cluster runs down the left edge, with the
  active one highlighted. **Left-click** switches the whole UI to that cluster and opens its Overview;
  **right-click** opens a per-cluster editor (label, color, environment, Hide, Forget). The **＋** at the
  bottom *adds* a kube.conf (instead of replacing), so clusters from several config files are aggregated
  in the rail — one active at a time; each remembers its source file. The rail collapses, and clusters
  can be hidden (reversibly) or a loaded file forgotten (KubeDesk only — your kube.conf is never edited).
- **Per-cluster environment + colors.** Tag a cluster `DEV` / `DEMO` / `STAGING` / `PROD` (auto-inferred
  from the context name when not set, matched on whole words), which colors its avatar — **green / orange
  / amber / red** — plus a thin **accent strip across the top of the window** in the active cluster's
  color, an at-a-glance "which cluster am I on" (and a red "you're in PROD") cue.
- **Overview dashboard.** A new **Overview** view (top of the sidebar) with a cluster header, stat tiles
  (Pods, Warnings, Deployments, Nodes, Namespaces — each clickable to jump), and compact Unhealthy-pods
  and Warning-events lists, all from data KubeDesk already computes.
- **Command palette covers clusters.** `Ctrl`/`Cmd`+`P` now lists every cluster (across all loaded
  configs), searchable by context name, custom label, and environment, plus the Overview view.

### Changed
- **Layout: no more full-width top bar.** The brand moves into the sidebar and the context/namespace/
  search/live/menu controls become a wrapping toolbar above the content — more vertical room, and the
  controls share a consistent height.
- **Status-bar errors no longer linger.** An error now auto-clears back to "Ready." after ~15s (and is
  replaced the moment any new status arrives), instead of staying red indefinitely.
- **Logs: the Detach button moved** to the end of the toolbar.

## [1.11.0] - 2026-10-31

### Added
- **Filter log lines.** A case-insensitive "Filter lines" box on the Logs tab (and detached log
  windows) shows only matching lines. The full log is kept in the background, so Copy and Save still
  capture everything, not just the filtered view.
- **Previous container logs.** A **Previous** toggle shows the prior, terminated container's logs
  (`kubectl logs --previous`) — the way to see why a crashed/restarted pod died. It disables Follow
  while on, since a dead container can't stream.
- **Timestamps toggle.** Prefix each log line with the server's timestamp; works both one-shot and
  while following.
- **Clear and Wrap controls.** A **⌫ Clear** button empties the view (the stream keeps appending after),
  and a **⤶ Wrap** toggle wraps long lines.
- **Pod-status dot in the log header.** A colored dot (green Running / amber Pending / red failing)
  reflects the pod's phase, mirroring the table's status chips.

## [1.10.1] - 2026-10-30

### Changed
- **Log follow stays put when you scroll up.** While following, the view now only auto-scrolls when
  you're already at the bottom — scroll up to read and new lines append silently below instead of
  yanking you down. A **↓ Jump to latest** pill appears while you're scrolled up (click it, or scroll
  back to the bottom, to re-pin). Applies to both the Logs tab and detached log windows.
- **Tidier Logs toolbar.** The refresh, follow, detach, copy and save buttons now share a consistent
  height, and the toolbar wraps onto a second line on a narrow window instead of clipping.

## [1.10.0] - 2026-10-30

### Added
- **Live log follow (`kubectl logs -f`).** The Logs tab gains a **▶ / ⏸** Follow button: instead of
  re-pulling a fixed tail with refresh, it streams new lines live as the container emits them, with
  auto-scroll. Following persists as you switch between pods, so you can leave it running through a
  rollout. Backed by a new `ClusterService.followLogs(...)` over the same API (no `kubectl`).
- **Detachable log window.** A **⧉ Detach** button pops the current pod's logs into a standalone
  window that keeps streaming on its own while you work in the main window — ideal for watching a
  deploy on a second monitor. It carries over the current container, tail size and follow state, you
  can open several at once (one per pod/container), and each frees its connection when closed.

### Changed
- **Internal: one shared cluster connection.** The `ClusterService` is now created once by the app
  and injected into the UI (rather than constructed inside it), and the kube.conf file controls moved
  onto the `ClusterService` interface. This is what lets the detached log windows share a single
  connection; no user-visible change.

## [1.9.0] - 2026-10-30

### Added
- **Command palette (`Ctrl`/`Cmd`+`P`).** A keyboard-driven quick-switcher: press `Ctrl`/`Cmd`+`P`,
  type to fuzzily filter across **contexts, namespaces, resource kinds, and views** (Health / Topology
  / Events), then ↑/↓ and `Enter` to jump there. It removes the app's most repeated gesture — hunting
  the context/namespace boxes and the sidebar with the mouse. Navigation-only for now; it reuses the
  existing selection handlers.

### Changed
- **MCP `describe_yaml` redacts Secret values.** Fetching a Secret over the MCP server previously
  returned the full manifest including the base64-encoded `data`/`stringData` values — the secret
  material itself. Those values are now replaced with `<redacted>` for agents (keys, type and
  structure are kept); KubeDesk's own YAML view is unchanged. Secret *listings* already showed only a
  key count, so they were never affected.

## [1.8.0] - 2026-10-30

### Added
- **Live mirror of the MCP agent's activity.** When an AI agent calls a read tool, KubeDesk now
  reflects it in the running window: a `🤖 agent · …` line in the status bar, and — with **Follow
  agent** on (default) — the UI reveals what the agent read, switching namespace/kind and opening the
  resource's **YAML** or **Logs** tab so you watch the agent work. The **☰ → MCP server…** panel gains
  a live **Recent activity** list of recent calls.
- **Apply handoff for agents.** The server's `initialize` response now tells the agent it is read-only
  and how a change actually reaches the cluster — by producing ready-to-apply YAML and pointing the
  user at the blue **+ Create** button (or a resource's **YAML → Apply**). The server stays strictly
  read-only; this just lets an agent be useful about writes without pretending it applied them.

### Changed
- **The MCP panel states its access level.** A `READ-ONLY` badge sits beside the power toggle, and a
  locked **Read-only ⇄ Read & write** switch makes the scope explicit — the write side is disabled
  until write tools (with their in-UI confirmation gate) exist.

## [1.7.0] - 2026-10-29

### Added
- **Experimental MCP server for AI agents.** KubeDesk can expose an embedded, **read-only**
  [Model Context Protocol](https://modelcontextprotocol.io) server (**☰ → MCP server…**) so an AI agent
  such as Claude can inspect your cluster over the same API the UI uses — **no `kubectl` required**. It
  speaks Streamable HTTP, binds **loopback only** (`127.0.0.1`, default port `7865`), and is **off by
  default**. Five read-only tools reuse the existing `ClusterService`: `health_scan`, `list_namespaces`,
  `list_resource`, `describe_yaml`, and `pod_logs` — context and namespace default to the current UI
  selection, and results are kept compact (server-side filters, capped log tails) to save the agent's
  tokens. A small panel with a power toggle shows the endpoint to paste into `claude mcp add`.
  Dev/testing use only; write actions (behind an in-UI confirmation) and a live in-UI mirror of the
  agent's view are planned.

## [1.6.1] - 2026-09-28

### Added
- **Copy buttons on Logs and Events.** The details panel's **Logs** and **Events** tabs get a **Copy**
  button in their toolbar (like Overview's **Copy all**) that puts the whole current text on the clipboard
  and confirms the line count in the status bar. The Events tab also gains a **refresh** button, so you
  can re-pull events without reopening the resource.

## [1.6.0] - 2026-09-06

### Added
- **Restart a pod.** Pods gain a **Restart (recreate)** action — in the table row menu, the topology /
  health context menus, and the details **Actions** menu. Kubernetes has no restart verb for pods, so this
  deletes the pod and lets its controller (ReplicaSet, StatefulSet, DaemonSet, Job) create a fresh
  replacement; the confirmation says so, and points to **Restart (rollout)** on the Deployment for a
  no-downtime restart of every replica. The action is hidden for *bare* pods (no controller — nothing would
  recreate them) and refused server-side as a second guard, and it follows the same RBAC gating as Delete.

## [1.5.1] - 2026-07-17

### Fixed
- **kubectl console now finds a kubectl next to the app.** The console previously ran a bare `kubectl`,
  so it only worked when kubectl was on the system `PATH` — a frequent miss on VDIs and packaged installs,
  where a `PATH` edit doesn't reach the already-running launcher. KubeDesk now resolves the binary in
  order: a `KUBEDESK_KUBECTL` env var (or `-Dkubedesk.kubectl=…` property) override, then a
  `kubectl`/`kubectl.exe` shipped next to the app (or a sibling/parent `bin/`), then the system `PATH` as
  before. Drop your own kubectl next to KubeDesk and the console works without touching `PATH`. No binary
  is bundled with the distribution.

## [1.5.0] - 2026-07-12

### Added
- **Embedded kubectl console.** A new **kubectl console** (☰ menu, or **Ctrl/Cmd+K**) opens a terminal
  drawer at the bottom of the window that runs *your own* `kubectl`. The active **context**, **namespace**,
  and **kubeconfig** are auto-injected on every command, so what you run always matches what's selected in
  the UI — type just the args (e.g. `get pods -o wide`). Output streams live with a **Stop** button for
  `-f`/`-w` commands, **↑/↓** command history, and selectable/copyable text. Commands run through the
  system shell, so pipes and redirects (`| grep`, `| jq`, `> file`) work. It defers to you when you type an
  explicit `-n`/`--all-namespaces`/`--context`. This is a command-runner, not a full TTY — interactive
  commands (`kubectl edit`, `exec -it`) still belong in the pod **Shell** tab; if `kubectl` isn't on your
  PATH the drawer says so (KubeDesk's own actions don't need it).

## [1.4.0] - 2026-07-06

### Added
- **Events view.** A new **Events** entry in the sidebar shows the namespace's event stream — the
  practical "namespace log" — as a table (Type, Reason, Object, Count, Last Seen, Message), most-recent
  first. **Warning** rows are tinted so problems stand out. This is where the reason for a failing pod,
  a scheduling issue, or an image-pull error usually shows up, all in one place.
- **Live pod metrics charts.** The pod details panel gains a **Metrics** tab with live **CPU** and
  **Memory** line charts that poll the Metrics API (every 10s, summed across containers) and plot usage
  over time — a quick read on whether a pod is climbing toward its limits. Requires metrics-server in
  the cluster; the tab notes when metrics aren't available.

## [1.3.1] - 2026-07-05

### Added
- **Proactive RBAC awareness.** On each namespace change, KubeDesk runs a `SelfSubjectRulesReview`
  (like `kubectl auth can-i --list`) and adapts the UI to your permissions: resource kinds you can't
  **list** are hidden from the sidebar (with their section header), **Delete / Scale / Restart** are
  disabled when you lack the verb, and **+ Create** is greyed out when you can't create. It **fails
  open** — if permissions can't be determined (or in all-namespaces mode), everything is shown and
  enabled, so nothing you can actually use is ever hidden. Broad/admin tokens see no change.

## [1.3.0] - 2026-07-05

### Added
- **Search across all listed namespaces.** The namespace dropdown gains an **"✿ All namespaces"**
  entry (when more than one namespace is listed). It lists the current kind in each listed namespace
  and merges them into one table with a leading **Namespace** column, so the search box spans them
  all. It queries each namespace individually rather than a single cluster-wide list, so it works
  even with namespace-scoped tokens. This mode is read-only (double-click / right-click → View YAML,
  Copy name, or "Go to this namespace"); pick a single namespace to act on a resource.

### Changed
- **Per-context namespace memory.** The last-used namespace is remembered per context and restored
  when you switch back — juggling multiple contexts no longer loses your namespace.
- **Copyable text.** The status message and the details Overview are now selectable and copyable
  (select + Ctrl+C, right-click Copy, or a "Copy all" button on the Overview) — handy for pasting an
  error or a resource's details into a ticket or chat.

## [1.2.8] - 2026-07-05

### Fixed
- **Never list resources cluster-wide by accident.** When the Namespace box is blank (e.g. a
  kubeconfig context that sets no default namespace), KubeDesk now falls back to the context's
  namespace and never issues a cluster-wide list — which a namespace-scoped token is forbidden from
  and which produced a confusing "pods is forbidden" error. If a request is still denied, the status
  bar now hints: *type your namespace in the Namespace box and press Enter.* (The Namespace box is
  editable, and your choice is remembered across restarts.)

## [1.2.7] - 2026-07-04

### Changed
- The bulk **"Clean up"** action now lives only in the **Health scan** (removed from the Pods
  toolbar) — the Health view is where terminal pods surface, so it's the natural home for clearing them.

## [1.2.6] - 2026-07-04

### Added
- **Auto-update.** On startup KubeDesk checks GitHub for a newer release and, if one exists, shows a
  dismissible banner under the toolbar. **Update now** downloads the installer for your OS (with a
  progress dialog) and launches it — a one-click upgrade. The check is async (never blocks startup),
  fails silently when offline, remembers a version you dismiss, and can be turned off with
  `updateCheckEnabled: false` in `~/.kubedesk/prefs.json`.
  (Note: the installers aren't code-signed yet, so Windows may still show an "unknown publisher"
  prompt during the update — that's expected.)
- **App menu (☰) + About dialog.** A new menu at the right of the toolbar with **Check for updates…**
  (manual re-check with feedback) and **About KubeDesk** (version, links, license).

## [1.2.5] - 2026-07-04

### Added
- **Bulk "Clean up" for terminal pods** — remove evicted/failed pods (and optionally completed ones)
  in one click instead of deleting them one by one. A **🧹 Clean up** menu on the Pods toolbar (shown
  only for Pods) and in the Health scan offers *"evicted / failed"* (`Phase=Failed`) or *"failed +
  completed"* (`Failed` + `Succeeded`). It counts matches first and confirms the exact number before
  deleting, and only ever targets terminal pods — Running/Pending pods are never touched, so live
  workloads are unaffected.

## [1.2.4] - 2026-07-03

### Fixed
- **Status bar stays visible on small screens.** The sidebar's nav list is now scrollable, so a short
  window (e.g. a small VDI display) shrinks/scrolls the list instead of forcing the whole layout taller
  than the screen and pushing the bottom status bar off-screen. "+ Create" stays pinned at the bottom.

## [1.2.3] - 2026-07-03

### Fixed
- **Works with namespace-scoped tokens** (common on AKS and other managed clusters). Listing
  namespaces cluster-wide is often forbidden for such tokens; KubeDesk no longer fails the whole load
  when that happens — it falls back to the namespace declared in your kubeconfig context so resources
  still load. The **namespace box is now editable**, so you can type a namespace even when they can't
  be enumerated.
- **Window fits the screen.** On smaller displays (e.g. a 1366×768 VDI) the window could open taller
  than the screen, hiding the bottom status bar until you maximized. It now sizes itself to the
  screen's work area and centers on-screen.
- **Status bar stays visible** when the window is short (the content area shrinks instead of pushing
  it off-screen), and **errors now render in red/bold** so a failed load can't be silently missed.

## [1.2.2] - 2026-07-03

### Added
- **Portable Windows build (no installer)** — a `KubeDesk-<version>-windows-portable.zip` is now
  published with each release. Unzip it and run `KubeDesk.exe`: no installer, no admin rights, and no
  Java needed (it bundles its own runtime). Ideal for locked-down environments such as a VDI where you
  can't run an `.msi`. The packaging script gained a `-Zip` switch (`./scripts/package.ps1 -Zip`) to
  produce the same zip locally.

## [1.2.1] - 2026-07-02

### Added
- **Health scan: not-ready pods** — flags a `Running` pod whose container is up but not `Ready`
  (e.g. a failing readiness probe): the pod is "up" but takes no traffic. Previously invisible to the
  phase and restart checks.
- **Health scan: suspended CronJobs** — flags CronJobs with `spec.suspend: true` (no new jobs will
  be scheduled).

### Changed
- **Health scan explains *why* a pod crashed** — CrashLoop / image-pull findings now include the
  container's last termination (exit code + reason, e.g. `last exit 137 (SIGKILL — often OOMKilled …)`),
  reusing the same diagnostic the pod details panel shows, so the reason survives even after the
  cluster's events have expired.

## [1.2.0] - 2026-07-02

### Added
- **Health scan** (sidebar → VIEWS → Health): a one-screen "what's wrong right now" — scans the
  current namespace (plus cluster Nodes) for resources failing common checks: CrashLoop /
  ImagePull / OOM / Evicted / unschedulable / high-restart pods, BestEffort (eviction-prone) pods,
  Deployments/StatefulSets/DaemonSets with replicas unavailable, failed Jobs, unbound PVCs,
  orphaned Services (selector matches no ready pods), and unhealthy / cordoned / pressured Nodes.
  Findings are ranked most-severe first with a plain-English reason; double-click a finding to
  inspect it, right-click to act (restart/scale/delete). One "Rescan" button, all over the API.

## [1.1.0] - 2026-07-02

### Added
- **YAML linting on the Create / Apply screen**: a live client-side syntax check (with line/column)
  updates as you type, plus a **Validate (dry-run)** button that runs a server-side dry-run apply to
  check documents against the cluster's real schema and admission — over the API, no kubectl.
- **Preferences persisted across restarts** (`~/.kubedesk/prefs.json`): remembers your custom
  kube.conf path, last context and namespace, and hidden table columns.
- **Persisted port-forwards**: active tunnels are saved on exit, and KubeDesk offers to re-open them
  on the next launch.
- **More pod columns**: **IP**, **QoS class** (eviction-risk indicator), and **Controlled By**
  (owning controller) — hidden by default, toggleable via the column menu.
- **Namespace topology view** (sidebar → VIEWS → Topology): a native, dependency-free
  Ingress → Service → Workload → Pods map with the *real* edges (Service→Pod by label selector,
  Deployment→Pod by ownership via ReplicaSet). Click a node to focus it and open its details in
  place; click a line to trace a connection; drag to pan, Ctrl+scroll to zoom.
- **Actions in the details panel**: an "Actions" menu in the details header (Restart/Scale for
  Deployments, Port-forward for Pods, Copy name/YAML, Delete) — so you can act on a resource opened
  from the topology *or* a table double-click, not only from the table's right-click menu. Scale now
  reads the current replica count live, so it prefills correctly from anywhere.
- **Right-click on topology nodes**: the same view/act menu (details, YAML, events, logs, restart,
  scale, port-forward, copy, delete) is available directly on a diagram node — reusing the shared
  handlers, so the table, details panel, and topology behave identically.

## [1.0.1] - 2026-07-01

### Added
- **Pod port-forwarding**, entirely over the Kubernetes API (no kubectl). Right-click a pod →
  **Port forward…**, choose the pod port (pre-filled from the pod's declared ports) and a local
  port, and the tunnel opens at `localhost:<port>`.
- **Active port-forwards manager** in the toolbar: a live list of tunnels with status, plus
  **Copy** (the local address), **Open** (in your browser), and **Stop**. Tunnels auto-close when
  the app exits.

### Notes
- Port-forwards are kept in memory for now — persisting them across restarts is planned (with a
  general preferences store).

## [1.0.0] - 2026-07-01

First stable release.

### Added
- Browse **12 resource kinds**: Pods, Deployments, StatefulSets, DaemonSets, Jobs, CronJobs,
  Services, Ingresses, ConfigMaps, Secrets, PVCs, Nodes — grouped in the sidebar.
- **Live updates** via watches, with **pause/resume**.
- **Details panel** with per-kind tabs: Overview / YAML / Events / Logs / Shell.
- **Pod failure diagnostics** — container state, exit codes, and eviction reasons (surfaces *why*
  a pod is failing even after its events have expired).
- **Per-pod CPU / Memory** columns from the Metrics API (degrades gracefully without metrics-server).
- **Management actions**: delete, rollout restart, scale, and edit-YAML-and-apply — with
  confirmations and per-kind context menus.
- **"+ Create / Apply YAML"** — create or update any resource(s) via server-side apply
  (multi-document, open-from-file), all over the API.
- **Pod exec** command console, **log save-to-file**, and per-kind **column show/hide**.
- **App icon** (ship's-helm wheel + container cube) and **native installers** for
  Windows (`.msi`), macOS (`.dmg`), and Linux (`.deb`/`.rpm`) built by CI.

### Tech
- Java 21 + JavaFX + AtlantaFX, talking directly to the Kubernetes API via fabric8 — no kubectl
  binary required for any operation.
