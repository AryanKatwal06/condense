<#
.SYNOPSIS
Installs Condense for Windows.

.DESCRIPTION
This script downloads the Condense executable for Windows from the GitHub releases page,
verifies its SHA-256 checksum, and places it in $env:USERPROFILE\.local\bin.
It also checks if the directory is in your PATH.

.EXAMPLE
iwr https://raw.githubusercontent.com/AryanKatwal06/code-condenser/main/install.ps1 -useb | iex
#>

param (
    [switch]$Help
)

if ($Help) {
    Write-Host "Usage: install.ps1 [-Help]"
    Write-Host ""
    Write-Host "Installs Condense, a high-performance CLI proxy for AI coding agents."
    Write-Host ""
    Write-Host "Parameters:"
    Write-Host "  -Help    Show this help message"
    Write-Host ""
    Write-Host "Environment Variables:"
    Write-Host "  CONDENSE_VERSION   Force install of a specific version (e.g. 1.0.1)"
    Write-Host "                     If unset, defaults to the latest GitHub release."
    exit 0
}

$ErrorActionPreference = 'Stop'

$Repo = "AryanKatwal06/code-condenser"
$Version = $env:CONDENSE_VERSION
if ([string]::IsNullOrWhiteSpace($Version) -or $Version -eq "`$`{project.version`}") {
    try {
        $Release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -UseBasicParsing
        $Version = $Release.tag_name.TrimStart('v')
    } catch {
        $Version = "1.0.1"
    }
}
$BaseUrl = "https://github.com/$Repo/releases/download/v$Version"
$BinaryName = "condense.exe"

# Architecture Detection
$Arch = $env:PROCESSOR_ARCHITECTURE
if ($Arch -eq "AMD64") {
    $Platform = "windows-x64"
} elseif ($Arch -eq "ARM64") {
    $Platform = "windows-aarch64"
} else {
    Write-Error "Unsupported architecture: $Arch"
    exit 1
}

$InstallDir = Join-Path $env:USERPROFILE ".local\bin"
if (-not (Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
}

$BinaryFilename = "condense-$Platform.exe"
$ChecksumsFilename = "checksums.txt"
$BinaryUrl      = "$BaseUrl/$BinaryFilename"
$ChecksumsUrl   = "$BaseUrl/$ChecksumsFilename"

$TempDir = Join-Path $env:TEMP "condense_install_$([guid]::NewGuid().Guid)"
New-Item -ItemType Directory -Force -Path $TempDir | Out-Null

try {
    Write-Host ""
    Write-Host "  Installing Condense v$Version"
    Write-Host "  Platform:   $Platform"
    Write-Host "  Install to: $InstallDir\$BinaryName"
    Write-Host ""

    Write-Host "  Downloading binary..."
    Invoke-WebRequest -Uri $BinaryUrl -OutFile "$TempDir\$BinaryFilename" -UseBasicParsing

    Write-Host "  Downloading checksums..."
    Invoke-WebRequest -Uri $ChecksumsUrl -OutFile "$TempDir\$ChecksumsFilename" -UseBasicParsing

    Write-Host "  Verifying checksum..."
    $ChecksumsContent = Get-Content "$TempDir\$ChecksumsFilename"
    $ExpectedLine = $ChecksumsContent | Where-Object { $_ -match [regex]::Escape($BinaryFilename) }
    if (-not $ExpectedLine) {
        Write-Error "  Could not find checksum for $BinaryFilename in checksums.txt"
        exit 1
    }
    $ExpectedHash = ($ExpectedLine -split "\s+")[0].ToLower()
    $ActualHash   = (Get-FileHash "$TempDir\$BinaryFilename" -Algorithm SHA256).Hash.ToLower()

    if ($ExpectedHash -ne $ActualHash) {
        Write-Error "  Checksum verification FAILED - binary may be corrupted."
        exit 1
    }
    Write-Host "  checksum OK"

    $InstallPath = Join-Path $InstallDir $BinaryName
    Copy-Item "$TempDir\$BinaryFilename" -Destination $InstallPath -Force
    Write-Host "  Installed to $InstallPath"

    # Add to PATH if needed
    $UserPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($UserPath -notlike "*$InstallDir*") {
        [Environment]::SetEnvironmentVariable('Path', "$UserPath;$InstallDir", 'User')
        Write-Host ""
        Write-Host "  Added $InstallDir to your PATH."
        Write-Host "  Restart your terminal for the change to take effect."
    }

    Write-Host ""
    Write-Host "  Condense installed successfully."
    Write-Host "  Run: condense --version"
    Write-Host "  Run: condense init -g    to hook into your AI tool"
    Write-Host ""
} finally {
    Remove-Item -Recurse -Force -Path $TempDir -ErrorAction SilentlyContinue
}
