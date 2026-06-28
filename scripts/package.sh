#!/usr/bin/env bash
#
# Build a native KubeDesk package with jpackage (macOS / Linux).
#
# Produces a self-contained app that bundles its own Java runtime — the target
# machine does NOT need Java installed. Default output is an "app-image" (a folder
# you can archive and share), which needs no extra tooling.
#
# Usage:
#   ./scripts/package.sh                # app-image (default)
#   ./scripts/package.sh dmg            # macOS .dmg installer
#   ./scripts/package.sh pkg            # macOS .pkg installer
#   ./scripts/package.sh deb            # Linux .deb  (needs dpkg + fakeroot)
#   ./scripts/package.sh rpm            # Linux .rpm  (needs rpmbuild)
#   ./scripts/package.sh app-image 1.2.3   # override version
#
set -euo pipefail

TYPE="${1:-app-image}"
APP_VERSION="${2:-0.1.0}"

cd "$(dirname "$0")/.."

JPACKAGE="${JAVA_HOME:-}/bin/jpackage"
if [ ! -x "$JPACKAGE" ]; then
  JPACKAGE="$(command -v jpackage || true)"
fi
[ -n "$JPACKAGE" ] || { echo "jpackage not found (need a JDK 17+); set JAVA_HOME." >&2; exit 1; }

echo "==> Building fat jar (mvn package)..."
mvn -q package

echo "==> Staging jar..."
rm -rf staging dist
mkdir -p staging
cp target/kubedesk.jar staging/

ARGS=(
  --type "$TYPE"
  --name KubeDesk
  --app-version "$APP_VERSION"
  --input staging
  --main-jar kubedesk.jar
  --main-class com.kubedesk.Launcher
  --dest dist
  --vendor "KubeDesk"
  --description "Lightweight Kubernetes desktop client"
)

# Pick a platform-appropriate icon if present (.icns for macOS, .png for Linux).
case "$(uname -s)" in
  Darwin) ICON="src/main/resources/icons/kubedesk.icns" ;;
  *)      ICON="src/main/resources/icons/kubedesk.png" ;;
esac
[ -f "$ICON" ] && ARGS+=(--icon "$ICON")

# Linux installers get a desktop entry.
case "$TYPE" in
  deb|rpm) ARGS+=(--linux-shortcut) ;;
esac

echo "==> Running jpackage ($TYPE)..."
"$JPACKAGE" "${ARGS[@]}"

echo "==> Done. Output in dist/"
ls -la dist
