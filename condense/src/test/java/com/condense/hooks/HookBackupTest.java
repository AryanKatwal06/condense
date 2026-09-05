package com.condense.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HookBackupTest {

    @TempDir
    Path tempDir;

    @Test
    void missingSourceNeedsNoBackup() throws Exception {
        Path data = tempDir.resolve("data");
        Path missing = tempDir.resolve("hooks.json");
        assertThat(HookBackup.copyExisting(data, HookTool.CURSOR, missing)).isNull();
        assertThat(Files.exists(data.resolve("backups"))).isFalse();
    }

    @Test
    void existingFileIsCopiedUnderBackups() throws Exception {
        Path data = tempDir.resolve("data");
        Path source = tempDir.resolve("hooks.json");
        Files.writeString(source, "{ \"keep\": true }\n");
        Path dest = HookBackup.copyExisting(data, HookTool.CURSOR, source);
        assertThat(dest).isNotNull();
        assertThat(dest.getParent()).isEqualTo(data.resolve("backups"));
        assertThat(dest.getFileName().toString()).startsWith("cursor-");
        assertThat(dest.getFileName().toString()).endsWith(".json");
        assertThat(Files.readString(dest)).contains("keep");
    }

    @Test
    void nullInputsAreFailClosed() {
        assertThatThrownBy(() -> HookBackup.copyExisting(null, HookTool.CURSOR, Path.of("x")))
            .isInstanceOf(java.io.IOException.class);
    }
}
