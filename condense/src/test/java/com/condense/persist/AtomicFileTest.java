package com.condense.persist;

import com.condense.config.ConfigWriter;
import com.condense.core.ConfigLoader;
import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.PlatformDirs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomicFileTest {

    @TempDir
    Path tempDir;

    @Test
    void diskFullLeavesDestinationUnchanged() throws Exception {
        Path dir = tempDir.resolve("disk-full");
        Files.createDirectories(dir);
        Path target = dir.resolve("config.toml");
        byte[] original = "keep-me".getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);

        AtomicFile atomic = new AtomicFile(FaultyDurableIo.diskFullAfter(2));
        assertThatThrownBy(() -> atomic.write(target, dir, "replacement-bytes".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("disk_full");

        assertThat(Files.readAllBytes(target)).isEqualTo(original);
        assertThat(countTemps(dir)).isZero();
    }

    @Test
    void readonlyLeavesDestinationUnchanged() throws Exception {
        Path dir = tempDir.resolve("readonly");
        Files.createDirectories(dir);
        Path target = dir.resolve("config.toml");
        byte[] original = "keep-me".getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);

        AtomicFile atomic = new AtomicFile(FaultyDurableIo.readOnly());
        assertThatThrownBy(() -> atomic.write(target, dir, "new".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("readonly");

        assertThat(Files.readAllBytes(target)).isEqualTo(original);
    }

    @Test
    void renameFailLeavesDestinationUnchanged() throws Exception {
        Path dir = tempDir.resolve("rename");
        Files.createDirectories(dir);
        Path target = dir.resolve("config.toml");
        byte[] original = "keep-me".getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);

        AtomicFile atomic = new AtomicFile(FaultyDurableIo.renameFails());
        assertThatThrownBy(() -> atomic.write(target, dir, "new".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("rename_fail");

        assertThat(Files.readAllBytes(target)).isEqualTo(original);
        assertThat(countTemps(dir)).isZero();
    }

    @Test
    void killBetweenWriteAndMoveLeavesTmpAndDestination() throws Exception {
        Path dir = tempDir.resolve("kill");
        Files.createDirectories(dir);
        Path target = dir.resolve("trust.json");
        byte[] original = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);

        AtomicFile atomic = new AtomicFile(FaultyDurableIo.skipRename());
        atomic.write(target, dir, "PARTIAL".getBytes(StandardCharsets.UTF_8), ".condense-trust-", ".tmp");

        assertThat(Files.readAllBytes(target)).isEqualTo(original);
        assertThat(countTemps(dir)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void symlinkSwapIsRejectedAndDestinationUntouched() throws Exception {
        Path dir = tempDir.resolve("inside");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(dir);
        Files.createDirectories(outside);
        Path real = outside.resolve("secret.json");
        byte[] original = "secret".getBytes(StandardCharsets.UTF_8);
        Files.write(real, original);
        Path target = dir.resolve("..").resolve("outside").resolve("secret.json");

        AtomicFile atomic = new AtomicFile();
        assertThatThrownBy(() -> atomic.write(target, dir, "pwned".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(Exception.class);

        assertThat(Files.readAllBytes(real)).isEqualTo(original);
    }

    @Test
    void partialConfigWriteLeavesLastGoodBytes() throws Exception {
        Path configDir = tempDir.resolve("cfg");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        PlatformDirs dirs = new IsolatedPlatformDirs(configDir, dataDir);
        ConfigLoader loader = new ConfigLoader(dirs);
        ConfigWriter writer = new ConfigWriter(dirs, loader);
        writer.set("tee.enabled", "true");
        Path configFile = dirs.getConfigFile();
        byte[] original = Files.readAllBytes(configFile);

        ConfigWriter failing = new ConfigWriter(dirs, loader, new AtomicFile(FaultyDurableIo.diskFullAfter(4)));
        assertThatThrownBy(() -> failing.set("tee.enabled", "false"))
            .isInstanceOf(Exception.class);

        assertThat(Files.readAllBytes(configFile)).isEqualTo(original);
    }

    private static long countTemps(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".tmp") || name.contains(".tmp");
            }).count();
        }
    }
}
