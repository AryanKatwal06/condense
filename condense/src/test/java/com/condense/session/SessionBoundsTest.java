package com.condense.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionBoundsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Bounds discovery by maxFiles parameter")
    void boundsMaxFiles() throws IOException {
        Path base = tempDir.resolve("sessions");
        Files.createDirectories(base);

        for (int i = 0; i < 10; i++) {
            Path file = base.resolve("sess_" + i + ".jsonl");
            Files.writeString(file, "{\"command\": \"cmd " + i + "\"}\n");
        }

        ClaudeCodeSessionReader reader = new ClaudeCodeSessionReader();
        List<Path> discovered = reader.discoverSessionFiles(base, 30, 4);

        assertThat(discovered).hasSize(4);
    }

    @Test
    @DisplayName("Filters out sessions older than maxAgeDays")
    void filtersByAge() throws IOException {
        Path base = tempDir.resolve("age_test");
        Files.createDirectories(base);

        Path recent = base.resolve("recent.jsonl");
        Path old = base.resolve("old.jsonl");

        Files.writeString(recent, "{\"command\": \"recent\"}\n");
        Files.writeString(old, "{\"command\": \"old\"}\n");

        // Set 'old' last modified time to 20 days ago
        Instant twentyDaysAgo = Instant.now().minus(20, ChronoUnit.DAYS);
        Files.setLastModifiedTime(old, FileTime.from(twentyDaysAgo));

        ClaudeCodeSessionReader reader = new ClaudeCodeSessionReader();
        List<Path> discovered = reader.discoverSessionFiles(base, 7, 10);

        assertThat(discovered).contains(recent);
        assertThat(discovered).doesNotContain(old);
    }

    @Test
    @DisplayName("Bounds file reading to maxBytes to avoid excessive memory allocation")
    void boundsMaxBytesRead() throws IOException {
        Path largeFile = tempDir.resolve("large.jsonl");
        // Write 100 lines of ~100 bytes each = ~10KB
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("{\"command\": \"echo line ").append(i).append("\", \"exitCode\": 0}\n");
        }
        Files.writeString(largeFile, sb.toString());

        ClaudeCodeSessionReader reader = new ClaudeCodeSessionReader();
        // Cap reading at 500 bytes
        SessionRecord record = reader.parseSession(largeFile, 500);

        // Only first few events should be parsed before reading hits the cap
        assertThat(record.events().size()).isLessThan(100);
        assertThat(record.events()).isNotEmpty();
    }
}
