package com.condense.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TranscriptResilienceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Empty files are handled gracefully without errors")
    void emptyFileHandled() throws IOException {
        Path emptyFile = tempDir.resolve("empty.jsonl");
        Files.writeString(emptyFile, "");

        ClaudeCodeSessionReader reader = new ClaudeCodeSessionReader();
        SessionRecord record = reader.parseSession(emptyFile, 1024 * 1024);

        assertThat(record).isNotNull();
        assertThat(record.events()).isEmpty();
    }

    @Test
    @DisplayName("Binary garbage and corrupted characters fail open")
    void binaryGarbageFailsOpen() throws IOException {
        Path corruptFile = tempDir.resolve("corrupt.json");
        byte[] garbage = new byte[] { (byte) 0xFF, (byte) 0xFE, 0x00, 0x12, 0x34, (byte) 0x88, 0x77 };
        Files.write(corruptFile, garbage);

        CursorSessionReader reader = new CursorSessionReader();
        assertThatCode(() -> {
            SessionRecord record = reader.parseSession(corruptFile, 1024 * 1024);
            assertThat(record.events()).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Truncated JSON lines do not crash parser and valid lines are preserved")
    void truncatedJsonLinesSkipped() throws IOException {
        String content = """
            {"command": "git status", "exitCode": 0}
            {"type": "tool_use", "id": "t1", "name": "Bash", "input": {"command": "incomplete...
            {"command": "pytest", "exitCode": 1, "output": "failed"}
            """;
        Path file = tempDir.resolve("truncated.jsonl");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        ClaudeCodeSessionReader reader = new ClaudeCodeSessionReader();
        SessionRecord record = reader.parseSession(file, 1024 * 1024);

        assertThat(record.events()).hasSize(2);
        assertThat(record.events().get(0).command()).isEqualTo("git status");
        assertThat(record.events().get(1).command()).isEqualTo("pytest");
    }

    @Test
    @DisplayName("Extreme line length (100KB) is handled without stack or memory exhaustion")
    void extremeLineLengthHandled() throws IOException {
        String giantOutput = "x".repeat(100_000);
        String line = "{\"command\": \"cat giant.txt\", \"exitCode\": 0, \"output\": \"" + giantOutput + "\"}\n";

        Path file = tempDir.resolve("giant.jsonl");
        Files.writeString(file, line, StandardCharsets.UTF_8);

        ClaudeCodeSessionReader reader = new ClaudeCodeSessionReader();
        SessionRecord record = reader.parseSession(file, 10 * 1024 * 1024);

        assertThat(record.events()).hasSize(1);
        assertThat(record.events().get(0).command()).isEqualTo("cat giant.txt");
        assertThat(record.events().get(0).rawOutputBytes()).isEqualTo(100_000);
    }
}
