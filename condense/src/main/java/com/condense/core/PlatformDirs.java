package com.condense.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves platform-appropriate configuration and data directories.
 *
 * <ul>
 *   <li>Linux: XDG_CONFIG_HOME/condense (config), XDG_DATA_HOME/condense (data),
 *       falling back to ~/.config/condense and ~/.local/share/condense</li>
 *   <li>macOS: ~/Library/Application Support/condense (both)</li>
 *   <li>Windows: %APPDATA%\condense (both)</li>
 * </ul>
 */
@ApplicationScoped
public class PlatformDirs {

    private static final Logger log = Logger.getLogger(PlatformDirs.class);

    /** Config directory. Created on first access if it does not exist. */
    public Path getConfigDir() {
        return ensureDir(resolveConfigBase());
    }

    /** Data directory. Created on first access if it does not exist. */
    public Path getDataDir() {
        return ensureDir(resolveDataBase());
    }

    /** Path to config.toml inside the config directory. */
    public Path getConfigFile() {
        return getConfigDir().resolve("config.toml");
    }

    /** Path to condense.db inside the data directory. */
    public Path getDatabaseFile() {
        return getDataDir().resolve("condense.db");
    }

    // ── private ──────────────────────────────────────────────────────────────

    private Path resolveConfigBase() {
        String os = os();
        if (os.contains("mac")) {
            return home("Library", "Application Support", "condense");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return appData != null
                ? Path.of(appData, "condense")
                : home("AppData", "Roaming", "condense");
        }
        // Linux / Unix
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return (xdg != null && !xdg.isBlank())
            ? Path.of(xdg, "condense")
            : home(".config", "condense");
    }

    private Path resolveDataBase() {
        String os = os();
        if (os.contains("mac")) {
            return home("Library", "Application Support", "condense");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return appData != null
                ? Path.of(appData, "condense")
                : home("AppData", "Roaming", "condense");
        }
        // Linux / Unix
        String xdg = System.getenv("XDG_DATA_HOME");
        return (xdg != null && !xdg.isBlank())
            ? Path.of(xdg, "condense")
            : home(".local", "share", "condense");
    }

    private static Path home(String... parts) {
        return Path.of(System.getProperty("user.home"), parts);
    }

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase();
    }

    private Path ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warnf("Could not create directory %s: %s", dir, e.getMessage());
        }
        return dir;
    }
}
