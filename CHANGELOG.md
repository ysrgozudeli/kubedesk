# Changelog

All notable changes to KubeDesk are documented here.
This project adheres to [Semantic Versioning](https://semver.org/).

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
