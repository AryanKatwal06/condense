package com.condense.core;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@link CondenseConfig} from the platform-specific config file.
 *
 * <p>If the config file does not exist, {@link CondenseConfig#defaults()} is returned.
 * If the config file exists but cannot be parsed, a warning is logged and defaults
 * are returned. The config is cached after the first load.
 */
@ApplicationScoped
public class ConfigLoader {

    private static final Logger log = Logger.getLogger(ConfigLoader.class);
    private static final TomlMapper TOML = com.condense.core.Mappers.TOML;

    @Inject
    PlatformDirs platformDirs;

    private volatile CondenseConfig cached;

    /**
     * Loads and returns the config. Cached after first call.
     * Thread-safe via double-checked locking.
     */
    public CondenseConfig load() {
        if (cached != null) return cached;
        synchronized (this) {
            if (cached != null) return cached;
            cached = loadFromDisk();
        }
        return cached;
    }

    /** Clears the config cache. Used in tests and writers. */
    public void invalidateCache() {
        synchronized (this) {
            cached = null;
        }
    }

    private CondenseConfig loadFromDisk() {
        Path configFile = platformDirs.getConfigFile();
        if (!Files.exists(configFile)) {
            log.debugf("No config file at %s; using defaults", configFile);
            return CondenseConfig.defaults();
        }
        try {
            CondenseConfig loaded = TOML.readValue(configFile.toFile(), CondenseConfig.class);
            log.debugf("Loaded config from %s", configFile);
            return loaded != null ? loaded : CondenseConfig.defaults();
        } catch (IOException e) {
            log.warnf("Failed to parse config at %s: %s — using defaults", configFile, e.getMessage());
            return CondenseConfig.defaults();
        }
    }
}
