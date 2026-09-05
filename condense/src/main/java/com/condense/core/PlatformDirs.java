package com.condense.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves platform-appropriate configuration and data directories.
 *
 * <p>Optional overrides, highest precedence, blank treated as unset:
 * <ul>
 *   <li>{@code CONDENSE_CONFIG_DIR} — config directory</li>
 *   <li>{@code CONDENSE_DATA_DIR} — data directory (analytics DB, tee files)</li>
 * </ul>
 *
 * <p>When those are unset:
 * <ul>
 *   <li>Linux: {@code XDG_CONFIG_HOME}/condense (config), {@code XDG_DATA_HOME}/condense (data),
 *       falling back to {@code ~/.config/condense} and {@code ~/.local/share/condense}</li>
 *   <li>macOS: {@code ~/Library/Application Support/condense} (both)</li>
 *   <li>Windows: {@code %APPDATA%}\condense (both)</li>
 * </ul>
 */
@ApplicationScoped
public class PlatformDirs {

    static final String CONFIG_DIR_ENV = "CONDENSE_CONFIG_DIR";
    static final String DATA_DIR_ENV = "CONDENSE_DATA_DIR";

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

    /** Returns the config directory path without creating it. */
    public Path resolveConfigDir() {
        return resolveConfigBase();
    }

    /** Returns the data directory path without creating it. */
    public Path resolveDataDir() {
        return resolveDataBase();
    }

    private Path resolveConfigBase() {
        return resolveConfigBase(
            os(),
            env(CONFIG_DIR_ENV),
            env("XDG_CONFIG_HOME"),
            env("APPDATA"),
            System.getProperty("user.home", "")
        );
    }

    private Path resolveDataBase() {
        return resolveDataBase(
            os(),
            env(DATA_DIR_ENV),
            env("XDG_DATA_HOME"),
            env("APPDATA"),
            System.getProperty("user.home", "")
        );
    }

    /**
     * Resolves the config directory from already-read environment values.
     * Package-private so unit tests can cover overrides without mutating process env.
     */
    static Path resolveConfigBase(
        String osName,
        String condenseConfigDir,
        String xdgConfigHome,
        String appData,
        String userHome
    ) {
        if (notBlank(condenseConfigDir)) {
            return Path.of(condenseConfigDir.trim());
        }
        String os = osName == null ? "" : osName.toLowerCase();
        if (os.contains("mac")) {
            return home(userHome, "Library", "Application Support", "condense");
        }
        if (os.contains("win")) {
            return notBlank(appData)
                ? Path.of(appData.trim(), "condense")
                : home(userHome, "AppData", "Roaming", "condense");
        }
        return notBlank(xdgConfigHome)
            ? Path.of(xdgConfigHome.trim(), "condense")
            : home(userHome, ".config", "condense");
    }

    /**
     * Resolves the data directory from already-read environment values.
     * Package-private so unit tests can cover overrides without mutating process env.
     */
    static Path resolveDataBase(
        String osName,
        String condenseDataDir,
        String xdgDataHome,
        String appData,
        String userHome
    ) {
        if (notBlank(condenseDataDir)) {
            return Path.of(condenseDataDir.trim());
        }
        String os = osName == null ? "" : osName.toLowerCase();
        if (os.contains("mac")) {
            return home(userHome, "Library", "Application Support", "condense");
        }
        if (os.contains("win")) {
            return notBlank(appData)
                ? Path.of(appData.trim(), "condense")
                : home(userHome, "AppData", "Roaming", "condense");
        }
        return notBlank(xdgDataHome)
            ? Path.of(xdgDataHome.trim(), "condense")
            : home(userHome, ".local", "share", "condense");
    }

    static String env(String name) {
        String value = System.getenv(name);
        if (notBlank(value)) {
            return value.trim();
        }
        String property = System.getProperty(name);
        return notBlank(property) ? property.trim() : null;
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Path home(String userHome, String... parts) {
        return Path.of(userHome == null ? "" : userHome, parts);
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
