package com.condense.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Detects whether the current condense binary was installed and managed
 * by a recognized system package manager (Scoop, Homebrew, or WinGet).
 *
 * <h2>Authoritative Reference Conventions</h2>
 * <ul>
 *   <li><b>Scoop (Windows)</b>:
 *     Per-user installs reside at {@code ~\scoop\apps\condense\<version>\} with shims at
 *     {@code ~\scoop\shims\condense.exe}. Global installs reside at
 *     {@code C:\ProgramData\scoop\apps\condense\<version>\} with shims at
 *     {@code C:\ProgramData\scoop\shims\condense.exe}. The substring checks
 *     {@code /scoop/apps/condense/} and {@code /scoop/shims/condense} accurately match
 *     both per-user and global layouts across versioned subdirectories and {@code current} junctions.
 *   </li>
 *   <li><b>Homebrew (macOS / Linux)</b>:
 *     Prefixes are {@code /opt/homebrew} (Apple Silicon macOS), {@code /usr/local} (Intel macOS),
 *     and {@code /home/linuxbrew/.linuxbrew} (Linuxbrew). Under Homebrew conventions, the actual
 *     binary is placed in {@code Cellar/condense/<version>/bin/condense} and symlinked into
 *     {@code bin/condense}. On Linux, {@code ProcessHandle.current().info().command()} reads
 *     {@code /proc/self/exe} which the Linux kernel resolves directly to the Cellar target path.
 *     On macOS, {@code ProcessHandle} invokes {@code proc_pidpath} which similarly resolves to the
 *     underlying executable vnode image in {@code Cellar/}. The patterns {@code /cellar/condense/},
 *     {@code /homebrew/bin/condense}, and {@code /.linuxbrew/bin/condense} cover both resolved
 *     and unresolved paths. Note that {@code /usr/local/bin/condense} is intentionally omitted from
 *     Homebrew pattern matching because {@code /usr/local/bin} is the standard manual install
 *     destination for standalone Unix installations; Homebrew Intel installations are detected via
 *     their resolved {@code /usr/local/Cellar/condense/...} path.
 *   </li>
 *   <li><b>WinGet (Windows)</b>:
 *     WinGet portable packages reside in {@code %LOCALAPPDATA%\Microsoft\WinGet\Packages\}
 *     (user scope) and {@code C:\Program Files\WinGet\Packages\} (machine scope), with directory
 *     naming formatted as {@code <PackageIdentifier>_<SourceId>_<hash>}. Because the package directory
 *     contains opaque hash suffixes, matching {@code /microsoft/winget/packages/} or
 *     {@code /winget/packages/} without requiring a literal {@code "condense"} segment is the
 *     deliberate and correct architectural design.
 *   </li>
 * </ul>
 */
public final class PackageManagerDetector {

    private PackageManagerDetector() {}

    public record Detection(String managerName, String uninstallCommand) {}

    /**
     * Inspects the given binary path to determine if it is managed by a package manager.
     *
     * @param binaryPath absolute or normalized path to the running executable
     * @return Optional containing the Detection if matched, or empty if standalone install
     */
    public static Optional<Detection> detect(Path binaryPath) {
        if (binaryPath == null) {
            return Optional.empty();
        }

        String pathStr = binaryPath.toAbsolutePath().normalize().toString();
        String normalized = pathStr.replace('\\', '/').toLowerCase(Locale.ROOT);

        // Scoop detection (Windows)
        if (normalized.contains("/scoop/apps/condense/") || normalized.contains("/scoop/shims/condense")) {
            return Optional.of(new Detection("Scoop", "scoop uninstall condense"));
        }

        // Homebrew detection (macOS / Linux)
        if (normalized.contains("/cellar/condense/")
                || normalized.contains("/homebrew/cellar/condense")
                || normalized.contains("/homebrew/bin/condense")
                || normalized.contains("/.linuxbrew/cellar/condense")
                || normalized.contains("/.linuxbrew/bin/condense")) {
            return Optional.of(new Detection("Homebrew", "brew uninstall condense"));
        }

        // WinGet detection (Windows)
        if (normalized.contains("/microsoft/winget/packages/") || normalized.contains("/winget/packages/")) {
            return Optional.of(new Detection("WinGet", "winget uninstall condense"));
        }

        return Optional.empty();
    }
}
