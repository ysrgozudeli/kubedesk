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

# 2) Installer (.exe or .msi) — requires the WiX Toolset v3 on PATH.
./scripts/package.ps1 -Type exe     # -> dist\KubeDesk-0.1.0.exe
./scripts/package.ps1 -Type msi     # -> dist\KubeDesk-0.1.0.msi
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
./scripts/package.sh dmg              # -> dist/KubeDesk-0.1.0.dmg
./scripts/package.sh pkg              # -> dist/KubeDesk-0.1.0.pkg
```

Needs **Xcode Command Line Tools** (`xcode-select --install`). For distribution *outside* your own
machines, macOS requires the app to be **code-signed and notarized** with an Apple Developer ID
(unsigned apps trigger Gatekeeper warnings). Pass signing options to jpackage
(`--mac-sign --mac-signing-key-user-name ...`) once you have a certificate.

### Linux (`.deb` / `.rpm`)

```bash
chmod +x scripts/package.sh          # once
./scripts/package.sh                  # app-image -> dist/KubeDesk/
./scripts/package.sh deb              # -> dist/kubedesk_0.1.0_amd64.deb   (needs dpkg + fakeroot)
./scripts/package.sh rpm              # -> dist/kubedesk-0.1.0.x86_64.rpm  (needs rpmbuild)
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

## License

[MIT](LICENSE) © Yasar Gozudeli

## Roadmap

- **v1 actions:** delete / scale / restart, exec into pod (terminal), port-forward, edit YAML.
- **v2:** apply manifests, Helm releases, multi-cluster, CRDs, RBAC views.
- Native installers via `jpackage` (`.msi` / `.dmg` / `.deb`).

[fabric8 Kubernetes client]: https://github.com/fabric8io/kubernetes-client
[AtlantaFX]: https://github.com/mkpaz/atlantafx
