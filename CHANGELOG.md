# Changelog

All notable changes to KubeDesk are documented here.
This project adheres to [Semantic Versioning](https://semver.org/).

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
