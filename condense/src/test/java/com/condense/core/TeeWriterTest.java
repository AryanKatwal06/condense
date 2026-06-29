package com.condense.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class TeeWriterTest {

    @Inject
    TeeWriter teeWriter;

    @Test
    void returnsNullWhenCommandSucceeds_defaultConfig() {
        // Default config: mode=FAILURES, so exit 0 → no tee file
        var result = new ExecutionResult(0, "some output", "", 10L);
        assertThat(teeWriter.maybeDump("git status", result)).isNull();
    }

    @Test
    void returnsNullForNullOutput() {
        var result = new ExecutionResult(0, "", "", 1L);
        assertThat(teeWriter.maybeDump("echo", result)).isNull();
    }

    @Test
    void createsFileWhenCommandFails_defaultConfig() throws Exception {
        var result = new ExecutionResult(128, "", "fatal: not a git repository", 5L);
        Path p = teeWriter.maybeDump("git status", result);

        assertThat(p).isNotNull();
        assertThat(Files.exists(p)).isTrue();

        String content = Files.readString(p);
        assertThat(content).startsWith("# condense tee dump");
        assertThat(content).contains("# command: git status");
        assertThat(content).contains("# exit:    128");
        assertThat(content).contains("fatal: not a git repository");

        Files.deleteIfExists(p);
    }

    @Test
    void teeFileContainsBothStdoutAndStderr() throws Exception {
        var result = new ExecutionResult(1, "stdout content", "stderr content", 42L);
        Path p = teeWriter.maybeDump("some command", result);

        assertThat(p).isNotNull();
        String content = Files.readString(p);
        assertThat(content).contains("## stdout");
        assertThat(content).contains("stdout content");
        assertThat(content).contains("## stderr");
        assertThat(content).contains("stderr content");

        Files.deleteIfExists(p);
    }

    @Test
    void teeFileNameHas8CharHashAndTimestamp() throws Exception {
        var result = new ExecutionResult(1, "", "error", 1L);
        Path p = teeWriter.maybeDump("git status", result);

        assertThat(p).isNotNull();
        String filename = p.getFileName().toString();
        // Format: {8hexchars}-{timestamp}.txt
        assertThat(filename).matches("[0-9a-f]{8}-\\d+\\.txt");

        Files.deleteIfExists(p);
    }
}
