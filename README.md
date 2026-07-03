# KubeDesk

A lightweight, cross-platform desktop client for managing Kubernetes clusters from a `kube.conf` file.

KubeDesk talks **directly to the cluster API server** (via the [fabric8 Kubernetes client]) using
your kubeconfig — **no `kubectl` binary is required** for any operation. The UI deliberately mirrors
the official Kubernetes Dashboard so users coming from there feel at home, positioned as a lighter
alternative to Lens.

> 📖 **New to Kubernetes?** Read
> [**Kubernetes for Spring Boot Microservice Developers: From Pods to Production**](https://medium.com/@ysrgozudeli/kubernetes-for-spring-boot-microservice-developers-from-pods-to-production-f9890aa432af)
> — a hands-on intro to Pods, Deployments, Services, and Ingress that uses KubeDesk to *see* it all running.

## Stack

| Layer        | Choice                                              |
|--------------|-----------------------------------------------------|
| Language     | Java 21 (LTS)                                        |
| UI           | JavaFX + [AtlantaFX] theme (Dashboard-like look)    |
| Kubernetes   | fabric8 `kubernetes-client` (direct API, no kubectl)|
| Build        | Maven                                               |
| Packaging    | `jpackage` — native installers built by CI          |

## Features

### Browse
- Load **any** `kube.conf` file (file picker) — not just `~/.kube/config`.
- Browse **contexts** and **namespaces** from the chosen config.
- **12 resource kinds** grouped in the sidebar: Pods, Deployments, StatefulSets, DaemonSets, Jobs,
  CronJobs, Services, Ingresses, ConfigMaps, Secrets, PVCs, Nodes — in a Dashboard-style table with
  status chips.
- **Live updates** via watches, with **pause/resume**.
- Client-side **search/filter**, and per-kind **column show/hide** (persisted).
- Extra pod columns: **CPU / Memory** (from the Metrics API, degrades gracefully without
  metrics-server), **IP**, **QoS class**, and **Controlled By**.

### Inspect
- **Details panel** with per-kind tabs: **Overview / YAML / Events / Logs / Shell**.
- **Pod failure diagnostics** — container state, exit codes, and eviction reasons (surfaces *why* a
  pod is failing even after its events have expired).
- **Log save-to-file**, and a resizable details split.

### Views
- **Topology** (sidebar → VIEWS → Topology): a native, dependency-free
  **Ingress → Service → Workload → Pods** map with the *real* edges (Service→Pod by label selector,
  Deployment→Pod by ownership via ReplicaSet). Click a node to focus + open details in place, click a
  line to trace a connection, drag to pan, Ctrl+scroll to zoom.
- **Health scan** (sidebar → VIEWS → Health): a one-screen "what's wrong right now" — scans the
  namespace and cluster Nodes for CrashLoop / ImagePull / OOM / Evicted / unschedulable / high-restart
  / BestEffort pods, unavailable workloads, failed Jobs, unbound PVCs, orphaned Services, and unhealthy
  / cordoned Nodes. Ranked most-severe first; double-click to inspect, right-click to act.

### Act (all over the API — no kubectl)
- **Management actions**: delete, rollout restart, scale, and edit-YAML-and-apply — with confirmations
  and per-kind context menus. The same actions are reachable from table rows, topology nodes, and the
  details-panel **Actions** menu.
- **"+ Create / Apply YAML"** — create or update any resource(s) via server-side apply (multi-document,
  open-from-file), with **live YAML linting** and a **Validate (dry-run)** button.
- **Pod exec** command console.
- **Port-forwarding**: open a tunnel to a pod port at `localhost:<port>`; a toolbar manager lists
  active tunnels (Copy / Open / Stop). Tunnels are persisted and offered for restore on next launch.

### Preferences
- Persisted across restarts (`~/.kubedesk/prefs.json`): custom kube.conf path, last context and
  namespace, hidden table columns, and active port-forwards.

## Architecture

The UI never touches fabric8 directly. Everything goes through the `ClusterService` interface, so a
future `kubectl`-backed implementation (or an MCP/CLI reuse of the same logic) can be added without
changing the UI. A generic table model (columns + string rows) drives all kinds.

```
JavaFX UI  ──►  ClusterService  ──►  Fabric8ClusterService  ──HTTPS/WSS──►  cluster API server
```

## Run (development)

```bash
mvn javafx:run
```

## Build a runnable jar

```bash
mvn package
java -jar target/kubedesk.jar
```

## Package a native app (jpackage)

KubeDesk ships as a self-contained app that **bundles its own Java runtime** — end users do **not**
need Java installed. Packaging uses the JDK's `jpackage` tool, so you need a **JDK 17+** (we use 21)
with `JAVA_HOME` set.

> Important: `jpackage` **cannot cross-compile**. You must build each platform's package **on that
> platform** (Windows builds `.exe`/`.msi`, macOS builds `.dmg`/`.pkg`, Linux builds `.deb`/`.rpm`).
> The recommended way to produce all three is a CI matrix (see below).

There are convenience scripts: **`scripts/package.ps1`** (Windows) and **`scripts/package.sh`**
(macOS/Linux). Both build the fat jar, stage it, and invoke `jpackage`.

### Windows (`.exe` / `.msi`)

```powershell
# 1) Self-contained app folder — no extra tooling. -> dist\KubeDesk\KubeDesk.exe
./scripts/package.ps1

# 1b) Portable zip — no installer, no admin, no Java. Unzip and run KubeDesk.exe.
#     Ideal for locked-down machines (e.g. a VDI) where you can't run an installer.
./scripts/package.ps1 -Zip          # -> dist\KubeDesk-1.2.2-windows-portable.zip

# 2) Installer (.exe or .msi) — requires the WiX Toolset v3 on PATH.
./scripts/package.ps1 -Type exe     # -> dist\KubeDesk-1.2.2.exe
./scripts/package.ps1 -Type msi     # -> dist\KubeDesk-1.2.2.msi
```

Installers (`exe`/`msi`) need the **[WiX Toolset v3](https://wixtoolset.org/)** on the `PATH`
(jpackage shells out to it). Install it, e.g.:

```powershell
winget install --id WiXToolset.WiXToolset    # or download the v3 installer from wixtoolset.org
```

The installer adds a Start-menu entry, a desktop shortcut, and an install-location chooser.

### macOS (`.dmg` / `.pkg`)

```bash
chmod +x scripts/package.sh          # once
./scripts/package.sh                  # app-image -> dist/KubeDesk.app
./scripts/package.sh dmg              # -> dist/KubeDesk-1.2.0.dmg
./scripts/package.sh pkg              # -> dist/KubeDesk-1.2.0.pkg
```

Needs **Xcode Command Line Tools** (`xcode-select --install`). For distribution *outside* your own
machines, macOS requires the app to be **code-signed and notarized** with an Apple Developer ID
(unsigned apps trigger Gatekeeper warnings). Pass signing options to jpackage
(`--mac-sign --mac-signing-key-user-name ...`) once you have a certificate.

### Linux (`.deb` / `.rpm`)

```bash
chmod +x scripts/package.sh          # once
./scripts/package.sh                  # app-image -> dist/KubeDesk/
./scripts/package.sh deb              # -> dist/kubedesk_1.2.0_amd64.deb   (needs dpkg + fakeroot)
./scripts/package.sh rpm              # -> dist/kubedesk-1.2.0.x86_64.rpm  (needs rpmbuild)
```

- `.deb` needs `dpkg-deb` + `fakeroot` (`sudo apt install fakeroot`).
- `.rpm` needs `rpmbuild` (`sudo dnf install rpm-build`).

### App icon (optional, per OS)

Drop a platform icon and the scripts pick it up automatically:

| OS      | File                                          |
|---------|-----------------------------------------------|
| Windows | `src/main/resources/icons/kubedesk.ico`       |
| macOS   | `src/main/resources/icons/kubedesk.icns`      |
| Linux   | `src/main/resources/icons/kubedesk.png`       |

### Notes

- The **app-image** is the simplest artifact (~174 MB, bundles the JRE). Zip `dist/KubeDesk/` to
  hand someone a runnable app with no installer.
- To shrink the bundle, a custom `jlink` runtime (only the modules KubeDesk uses) can roughly halve
  the size — verify TLS/auth still work afterward.

## Releases (CI)

A GitHub Actions workflow (`.github/workflows/release.yml`) builds the native installers for all
three platforms on their own runners (jpackage can't cross-compile):

| Platform | Artifact                              |
|----------|---------------------------------------|
| Windows  | `.msi` (WiX) **+ portable `.zip`**     |
| macOS    | `.dmg`                                 |
| Linux    | `.deb` and `.rpm`                      |

> The Windows **portable zip** (`KubeDesk-<version>-windows-portable.zip`) needs no installer, no
> admin rights, and no Java — unzip it and run `KubeDesk.exe`. Handy for locked-down environments
> (e.g. a VDI). Note: strict endpoint policies (AppLocker/WDAC, SmartScreen) can still block unsigned
> executables — test it in your environment.

**Cut a release:**

```bash
git tag v1.2.0
git push origin v1.2.0
```

Pushing a `v*` tag builds every installer and publishes a **GitHub Release** with them attached (using
the matching [CHANGELOG](CHANGELOG.md) section as the release body). You can also trigger the workflow
manually from the **Actions** tab ("Build distributions" → *Run workflow*); manual runs upload the
installers as **workflow artifacts** instead of creating a release.

> macOS builds are unsigned, so Gatekeeper will warn end users until the app is signed/notarized
> with an Apple Developer ID. Add `--mac-sign ...` options (and the signing secrets) to enable that.

## License

[MIT](LICENSE) © Yasar Gozudeli

## Roadmap

Shipped through **v1.2.0**: 12 resource kinds, live watches, details panel (incl. exec + logs), pod
diagnostics, management actions, Create/Apply with lint + dry-run, port-forwarding, preferences,
topology view, and health scan.

Candidate next:
- **Cluster-wide Events view** (the practical "cluster log").
- **Multi-pod log tailing** (stern-style) and optional Loki/cloud-logging integration.
- **Headless health CLI** for CI/CD gates (reuses `ClusterService.healthCheck`).
- **MCP server** for AI agents (read-only by default, reusing `ClusterService`).
- Live CPU/Memory graphs, all-namespaces view + global search, Sealed Secrets, generic CRD support,
  and macOS signing/notarization.

[fabric8 Kubernetes client]: https://github.com/fabric8io/kubernetes-client
[AtlantaFX]: https://github.com/mkpaz/atlantafx
