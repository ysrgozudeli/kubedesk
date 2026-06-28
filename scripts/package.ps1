<#
.SYNOPSIS
    Build a native KubeDesk package with jpackage.

.DESCRIPTION
    Produces a self-contained app that bundles its own Java runtime (no JRE needed on the
    target machine). Default output is an "app-image" (a folder you can zip and share),
    which needs no extra tooling. Pass -Type msi or -Type exe to build a Windows installer
    (requires the WiX Toolset v3 on PATH: https://wixtoolset.org/).

.EXAMPLE
    ./scripts/package.ps1                 # app-image  -> dist\KubeDesk\KubeDesk.exe
    ./scripts/package.ps1 -Type msi       # installer  -> dist\KubeDesk-<ver>.msi
#>
param(
    [ValidateSet("app-image", "msi", "exe")]
    [string]$Type = "app-image",
    [string]$AppVersion = "0.1.0"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
if (-not (Test-Path $jpackage)) { throw "jpackage not found at $jpackage (need a JDK 17+ in JAVA_HOME)." }

Write-Host "==> Building fat jar (mvn package)..." -ForegroundColor Cyan
mvn -q package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }

Write-Host "==> Staging jar..." -ForegroundColor Cyan
Remove-Item -Recurse -Force staging, dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force staging | Out-Null
Copy-Item target\kubedesk.jar staging\

$jpArgs = @(
    "--type", $Type,
    "--name", "KubeDesk",
    "--app-version", $AppVersion,
    "--input", "staging",
    "--main-jar", "kubedesk.jar",
    "--main-class", "com.kubedesk.Launcher",
    "--dest", "dist",
    "--vendor", "KubeDesk",
    "--description", "Lightweight Kubernetes desktop client"
)

# Use a custom icon if one is provided.
$icon = "src\main\resources\icons\kubedesk.ico"
if (Test-Path $icon) { $jpArgs += @("--icon", $icon) }

# Installer-only niceties: Start-menu entry, desktop shortcut, install-dir chooser.
if ($Type -ne "app-image") {
    $jpArgs += @("--win-shortcut", "--win-menu", "--win-dir-chooser", "--win-per-user-install")
}

Write-Host "==> Running jpackage ($Type)..." -ForegroundColor Cyan
& $jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed (for msi/exe make sure WiX v3 is on PATH)." }

Write-Host "==> Done. Output in dist\" -ForegroundColor Green
Get-ChildItem dist | Select-Object Name, Length
