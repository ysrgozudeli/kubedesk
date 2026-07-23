# Screenshots & GIFs

Assets embedded by the main [README](../../README.md).

## In use

| File | Shows |
|------|-------|
| `hero.gif` | Load a kubeconfig → browse the `demo` namespace live → open a pod. The header hero. |
| `logs-shell.gif` | Stream a pod's logs and open an interactive shell from the details panel. |

## Optional — nice to add later

Drop any of these in and wire them into the README's **In action** section. Capture with
**Win+Shift+S** (still) or [ScreenToGif](https://www.screentogif.com/) (animated):

| Suggested file | Shows |
|----------------|-------|
| `topology.png` | The `Ingress → Service → Workload → Pods` map. |
| `health.png` | The Health scan with findings ranked by severity (e.g. the `bad-image` pod). |
| `apply-yaml.png` | Create / Apply YAML with the **Validate (dry-run)** result. |

## Recording tips

- **Keep GIFs small** — aim for **< 5 MB**, **≤ 20s**, **12–15 fps**. Crop tight to the app window.
- Use a real (or `kind`/`minikube`) cluster with a few workloads so tables aren't empty. Redact
  private cluster/context names.
- Pick one theme (light or dark) and use it for every asset so the set looks cohesive.
