package com.condense.persist;

import com.condense.core.SafePathValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OrphanSweepTest {

    @TempDir
    Path tempDir;

    @Test
    void deletesKnownTempsAndSkipsWal() throws Exception {
        Path config = tempDir.resolve("config");
        Path data = tempDir.resolve("data");
        Files.createDirectories(config);
        Files.createDirectories(data);
        Path trustTmp = config.resolve("trust.json.tmp");
        Path configTmp = config.resolve(".condense-config-abc.toml.tmp");
        Path hookTmp = data.resolve(".condense-hook-xyz.tmp");
        Path wal = data.resolve("condense.db-wal");
        Path shm = data.resolve("condense.db-shm");
        Path keep = data.resolve("write-failures.json");
        Files.writeString(trustTmp, "partial");
        Files.writeString(configTmp, "partial");
        Files.writeString(hookTmp, "partial");
        Files.writeString(wal, "wal");
        Files.writeString(shm, "shm");
        Files.writeString(keep, "{}");

        OrphanSweep.Result result = OrphanSweep.sweep(config, data);
        assertThat(result.deleted()).isEqualTo(3);
        assertThat(Files.exists(trustTmp)).isFalse();
        assertThat(Files.exists(configTmp)).isFalse();
        assertThat(Files.exists(hookTmp)).isFalse();
        assertThat(Files.readString(wal)).isEqualTo("wal");
        assertThat(Files.readString(shm)).isEqualTo("shm");
        assertThat(Files.readString(keep)).isEqualTo("{}");
        assertThat(SafePathValidator.isKnownCondenseTemp("trust.json.tmp")).isTrue();
        assertThat(SafePathValidator.isKnownCondenseTemp("condense-dotnet-1234")).isTrue();
        assertThat(SafePathValidator.isKnownCondenseTemp("condense.db-wal")).isFalse();
    }
}
