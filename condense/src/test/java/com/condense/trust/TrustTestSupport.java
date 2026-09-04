package com.condense.trust;

import com.condense.core.PlatformDirs;
import com.condense.filter.pipeline.config.FilterOverrideLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/**
 * Test helper that writes a matching trust.json for a planted project override.
 */
public final class TrustTestSupport {

    private TrustTestSupport() {}

    public static PlatformDirs dirs(Path configDir) {
        return new PlatformDirs() {
            @Override
            public Path resolveConfigDir() {
                return configDir;
            }

            @Override
            public Path getConfigDir() {
                return configDir;
            }
        };
    }

    public static void trustProject(PlatformDirs dirs, Path projectDir) throws IOException {
        trustProject(dirs, projectDir, EnumSet.allOf(Capability.class));
    }

    public static void trustProject(PlatformDirs dirs, Path projectDir, Set<Capability> grants) throws IOException {
        Path file = projectDir.resolve(".condense/filters.toml");
        byte[] bytes = Files.readAllBytes(file);
        new TrustGate(dirs).accept(file.toRealPath(), bytes, grants);
    }

    public static FilterOverrideLoader isolatedLoader(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        return new FilterOverrideLoader(dirs(configDir));
    }

    public static FilterOverrideLoader trustedLoader(Path configDir, Path projectDir) throws IOException {
        Files.createDirectories(configDir);
        PlatformDirs dirs = dirs(configDir);
        trustProject(dirs, projectDir);
        return new FilterOverrideLoader(dirs);
    }
}
