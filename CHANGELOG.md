# Changelog

All notable changes to KubeDesk are documented here.
This project adheres to [Semantic Versioning](https://semver.org/).

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
