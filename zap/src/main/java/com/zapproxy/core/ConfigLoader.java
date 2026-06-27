package com.zapproxy.core;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@link ZapConfig} from the platform-specific config file.
 *
 * <p>If the config file does not exist, {@link ZapConfig#defaults()} is returned.
 * If the config file exists but cannot be parsed, a warning is logged and defaults
 * are returned. The config is cached after the first load.
 */
@ApplicationScoped
public class ConfigLoader {

    private static final Logger log = Logger.getLogger(ConfigLoader.class);
    private static final TomlMapper TOML = new TomlMapper();

    @Inject
    PlatformDirs platformDirs;

    private volatile ZapConfig cached;

    /**
     * Loads and returns the config. Cached after first call.
     * Thread-safe via double-checked locking.
     */
    public ZapConfig load() {
        if (cached != null) return cached;
        synchronized (this) {
            if (cached != null) return cached;
            cached = loadFromDisk();
        }
        return cached;
    }

    /** Clears the config cache. Used in tests. */
    void invalidateCache() {
        synchronized (this) {
            cached = null;
        }
    }

    private ZapConfig loadFromDisk() {
        Path configFile = platformDirs.getConfigFile();
        if (!Files.exists(configFile)) {
            log.debugf("No config file at %s; using defaults", configFile);
            return ZapConfig.defaults();
        }
        try {
            ZapConfig loaded = TOML.readValue(configFile.toFile(), ZapConfig.class);
            log.debugf("Loaded config from %s", configFile);
            return loaded != null ? loaded : ZapConfig.defaults();
        } catch (IOException e) {
            log.warnf("Failed to parse config at %s: %s — using defaults", configFile, e.getMessage());
            return ZapConfig.defaults();
        }
    }
}
