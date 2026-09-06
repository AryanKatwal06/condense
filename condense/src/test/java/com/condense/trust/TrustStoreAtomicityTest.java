package com.condense.trust;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.PlatformDirs;
import com.condense.persist.AtomicFile;
import com.condense.persist.FaultyDurableIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustStoreAtomicityTest {

    @TempDir
    Path tempDir;

    @Test
    void partialWriteLeavesLastGoodBytes() throws Exception {
        Path config = tempDir.resolve("config");
        Path data = tempDir.resolve("data");
        Files.createDirectories(config);
        PlatformDirs dirs = new IsolatedPlatformDirs(config, data);
        TrustStore store = new TrustStore(dirs);
        Path file = config.resolve("filters.toml");
        Files.writeString(file, "v1");
        store.put(new TrustRecord(
            file.toAbsolutePath().toString(),
            TrustStore.sha256Hex(Files.readAllBytes(file)),
            List.of("reduce"),
            "2020-01-01T00:00:00Z"));
        Path trust = dirs.resolveConfigDir().resolve(TrustStore.FILE_NAME);
        byte[] original = Files.readAllBytes(trust);

        TrustStore failing = new TrustStore(dirs, new AtomicFile(FaultyDurableIo.diskFullAfter(8)));
        assertThatThrownBy(() -> failing.put(new TrustRecord(
            file.toAbsolutePath().toString(),
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            List.of("reduce"),
            "2020-01-02T00:00:00Z")))
            .isInstanceOf(RuntimeException.class);

        assertThat(Files.readAllBytes(trust)).isEqualTo(original);
    }
}
