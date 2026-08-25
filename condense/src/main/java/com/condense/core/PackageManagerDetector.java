package com.condense.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Detects whether the current condense binary was installed and managed
 * by a recognized system package manager (Scoop, Homebrew, or WinGet).
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
