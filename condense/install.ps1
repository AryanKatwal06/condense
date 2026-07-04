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

$BinaryFilename = "condense-$Platform"
$ChecksumFilename = "condense-${Platform}.sha256"
$BinaryUrl = "$BaseUrl/$BinaryFilename"
$ChecksumUrl = "$BaseUrl/$ChecksumFilename"

$TempDir = Join-Path $env:TEMP "condense_install_$([guid]::NewGuid().Guid)"
New-Item -ItemType Directory -Force -Path $TempDir | Out-Null

try {
    Write-Host "`n  Installing Condense v$Version"
    Write-Host "  Repository: https://github.com/$Repo"
    Write-Host "`n  Platform:   $Platform"
    Write-Host "  Install to: $InstallDir\$BinaryName`n"

    Write-Host "  Downloading $BinaryFilename..."
    Invoke-WebRequest -Uri $BinaryUrl -OutFile "$TempDir\$BinaryFilename" -UseBasicParsing

    Write-Host "  Downloading $ChecksumFilename..."
    Invoke-WebRequest -Uri $ChecksumUrl -OutFile "$TempDir\$ChecksumFilename" -UseBasicParsing

    Write-Host "  Verifying SHA-256 checksum..."
    $ExpectedChecksum = (Get-Content "$TempDir\$ChecksumFilename").Trim().Split(" ")[0]
    $ActualChecksum = (Get-FileHash "$TempDir\$BinaryFilename" -Algorithm SHA256).Hash.ToLower()

    if ($ExpectedChecksum.ToLower() -ne $ActualChecksum) {
        Write-Error "`n  Error: checksum verification FAILED."
        Write-Error "  The downloaded binary may be corrupted or tampered with."
        exit 1
    }
    Write-Host "  ✓ Checksum OK"

    $InstallPath = Join-Path $InstallDir $BinaryName
    Copy-Item "$TempDir\$BinaryFilename" -Destination $InstallPath -Force

    Write-Host "  ✓ Installed to $InstallPath`n"

    $PathDirs = ($env:PATH).Split(';')
    if ($PathDirs -notcontains $InstallDir) {
        Write-Host "  Note: Add $InstallDir to your PATH to use condense from anywhere:"
        Write-Host "  [Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path', 'User') + ';$InstallDir', 'User')"
    }

    $VersionOut = & $InstallPath --version 2>&1
    if ($LASTEXITCODE -eq 0 -or $VersionOut) {
        Write-Host "`n  Condense installed successfully!"
        Write-Host "  Run 'condense --help' to get started."
        Write-Host "  Run 'condense init -g' to install AI tool hooks.`n"
    } else {
        Write-Warning "  Binary installed but 'condense --version' failed."
    }
} finally {
    Remove-Item -Recurse -Force -Path $TempDir
}
