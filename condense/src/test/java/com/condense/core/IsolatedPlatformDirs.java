package com.condense.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link PlatformDirs} that never touches the developer's real config or data dirs.
 */
public final class IsolatedPlatformDirs extends PlatformDirs {

    private final Path configDir;
    private final Path dataDir;

    public IsolatedPlatformDirs(Path configDir, Path dataDir) {
        this.configDir = configDir;
        this.dataDir = dataDir;
    }

    @Override
    public Path resolveConfigDir() {
        return configDir;
    }

    @Override
    public Path resolveDataDir() {
        return dataDir;
    }

    @Override
    public Path getConfigDir() {
        return ensure(configDir);
    }

    @Override
    public Path getDataDir() {
        return ensure(dataDir);
    }

    private static Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }
}
