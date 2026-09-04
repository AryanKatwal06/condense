package com.condense.trust;

import com.condense.core.PlatformDirs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrustStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void putFindAndRemoveByCanonicalPath() throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        TrustStore store = new TrustStore(dirs(configDir));

        Path file = tempDir.resolve("proj/.condense/filters.toml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "hello");
        Path canonical = file.toRealPath();

        store.put(new TrustRecord(canonical.toString(), TrustStore.sha256Hex("hello".getBytes(StandardCharsets.UTF_8)),
            List.of("reduce"), "2026-09-04T00:00:00Z"));

        assertThat(store.find(canonical)).isPresent();
        assertThat(store.find(canonical).orElseThrow().sha256())
            .isEqualTo(TrustStore.sha256Hex("hello".getBytes(StandardCharsets.UTF_8)));

        store.remove(canonical);
        assertThat(store.find(canonical)).isEmpty();
    }

    @Test
    void sha256IsStableHex() {
        assertThat(TrustStore.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    private static PlatformDirs dirs(Path configDir) {
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
}
