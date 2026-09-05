package com.condense.propose;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProposeWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writeCreatesProposedSidecarNotLiveOverride() throws Exception {
        Path work = Files.createDirectories(tempDir.resolve("proj"));
        Files.createDirectories(work.resolve(".git"));
        Files.writeString(work.resolve("pnpm-lock.yaml"), "lockfileVersion: '9.0'\n");
        ProposeService service = new ProposeService();
        ProposeReport report = service.propose(work, work, null);
        Path written = new ProposeWriter(service).write(work, report);
        assertThat(written.getFileName().toString()).isEqualTo("filters.toml.proposed");
        assertThat(work.resolve(".condense").resolve("filters.toml")).doesNotExist();
        assertThat(written).exists();
        String body = Files.readString(written);
        assertThat(body).startsWith("schema_version = 1");
        assertThat(body).contains("[filters.\"pnpm install\"]");
    }

    @Test
    void writeToLiveFiltersTomlIsRefused() throws Exception {
        Path work = Files.createDirectories(tempDir.resolve("proj"));
        Files.createDirectories(work.resolve(".git"));
        ProposeService service = new ProposeService();
        ProposeReport report = service.propose(work, work, null);
        Path live = work.resolve(".condense").resolve("filters.toml");
        Files.createDirectories(live.getParent());
        assertThatThrownBy(() -> new ProposeWriter(service).writeTo(live, work, report))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("filters.toml");
        assertThat(live).doesNotExist();
    }
}
