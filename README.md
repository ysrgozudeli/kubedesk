# KubeDesk

A lightweight, cross-platform desktop client for managing Kubernetes clusters from a `kube.conf` file.

KubeDesk talks **directly to the cluster API server** (via the [fabric8 Kubernetes client]) using
your kubeconfig — **no `kubectl` binary is required**. The UI deliberately mirrors the official
Kubernetes Dashboard so users coming from there feel at home.

## Stack

| Layer        | Choice                                             |
|--------------|----------------------------------------------------|
| Language     | Java 21 (LTS)                                       |
| UI           | JavaFX + [AtlantaFX] theme (Dashboard-like look)   |
| Kubernetes   | fabric8 `kubernetes-client` (direct API, no kubectl)|
| Build        | Maven                                              |
| Packaging    | `jpackage` (native installers — planned)           |

## MVP features

- Load **any** `kube.conf` file (file picker) — not just `~/.kube/config`.
- Browse **contexts** and **namespaces** from the chosen config.
- View **Pods, Deployments, Services, Nodes** in a Dashboard-style table with status chips.
- **Pod-health ring** summarising pod phases per namespace.
- Client-side **search/filter** across the current table.

## Architecture

The UI never touches fabric8 directly. Everything goes through the `ClusterService` interface, so a
future `kubectl`-backed implementation can be added for niche operations without changing the UI.

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

Produces a self-contained app that **bundles its own Java runtime** — end users do not need
Java installed.

```powershell
# Self-contained app folder (no extra tooling needed) -> dist\KubeDesk\KubeDesk.exe
./scripts/package.ps1

# Windows installer (requires the WiX Toolset v3 on PATH) -> dist\KubeDesk-0.1.0.msi
./scripts/package.ps1 -Type msi
```

- **app-image** (~174 MB) works out of the box; zip `dist\KubeDesk\` to distribute.
- **msi/exe** installers need [WiX v3](https://wixtoolset.org/) on the PATH; they add a
  Start-menu entry, desktop shortcut, and an install-location chooser.
- Drop a `src/main/resources/icons/kubedesk.ico` to brand the launcher; the script picks it up
  automatically.

On macOS/Linux the same `jpackage` invocation produces `.dmg`/`.pkg` and `.deb`/`.rpm`
respectively (run jpackage on that OS).

## Roadmap

- **v1 actions:** delete / scale / restart, exec into pod (terminal), port-forward, edit YAML.
- **v2:** apply manifests, Helm releases, multi-cluster, CRDs, RBAC views.
- Native installers via `jpackage` (`.msi` / `.dmg` / `.deb`).

[fabric8 Kubernetes client]: https://github.com/fabric8io/kubernetes-client
[AtlantaFX]: https://github.com/mkpaz/atlantafx
