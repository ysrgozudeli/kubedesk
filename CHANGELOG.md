# Changelog

All notable changes to KubeDesk are documented here.
This project adheres to [Semantic Versioning](https://semver.org/).

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
