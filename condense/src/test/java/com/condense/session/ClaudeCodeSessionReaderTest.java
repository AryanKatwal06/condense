package com.condense.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCodeSessionReaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Parses tool_use and tool_result pairs with chronology and durations")
    void parsesToolUseAndResults() throws IOException {
        String transcript = """
            {"type":"tool_use","id":"call_1","name":"Bash","input":{"command":"git status"},"timestamp":"2026-09-09T10:00:00Z"}
            {"type":"tool_result","tool_use_id":"call_1","content":"On branch main\\nnothing to commit","is_error":false,"timestamp":"2026-09-09T10:00:01Z"}
            {"type":"tool_use","id":"call_2","name":"Bash","input":{"command":"cargo test --token=secret1234567890"},"timestamp":"2026-09-09T10:00:05Z"}
            {"type":"tool_result","tool_use_id":"call_2","content":"error: test failed","is_error":true,"timestamp":"2026-09-09T10:00:08Z"}
            """;

        Path sessionFile = tempDir.resolve("session_01.jsonl");
        Files.writeString(sessionFile, transcript);

        ClaudeCodeSessionReader reader = new ClaudeCodeSessionReader();
        SessionRecord record = reader.parseSession(sessionFile, 1024 * 1024);

        assertThat(record.sessionId()).isEqualTo("session_01");
        assertThat(record.agentFormat()).isEqualTo(AgentTranscriptFormat.CLAUDE_CODE);
        assertThat(record.events()).hasSize(2);

        SessionRecord.SessionEvent event1 = record.events().get(0);
        assertThat(event1.command()).isEqualTo("git status");
        assertThat(event1.exitCode()).isEqualTo(0);
        assertThat(event1.durationMillis()).isEqualTo(1000L);
        assertThat(event1.outputSnippet()).contains("On branch main");

        SessionRecord.SessionEvent event2 = record.events().get(1);
        assertThat(event2.command()).contains("cargo test --token=[REDACTED_SECRET]");
        assertThat(event2.command()).doesNotContain("secret1234567890");
        assertThat(event2.exitCode()).isEqualTo(1);
        assertThat(event2.durationMillis()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("Discovers session files filtered by age and bounded by count")
    void discoversSessionFiles() throws IOException {
        Path projectDir = tempDir.resolve(".claude").resolve("projects").resolve("my-proj");
        Files.createDirectories(projectDir);

        Path s1 = projectDir.resolve("s1.jsonl");
        Path s2 = projectDir.resolve("s2.jsonl");
        Path ignoredTxt = projectDir.resolve("notes.txt");

        Files.writeString(s1, "{\"command\": \"ls\"}\n");
        Files.writeString(s2, "{\"command\": \"pwd\"}\n");
        Files.writeString(ignoredTxt, "some notes\n");

        ClaudeCodeSessionReader reader = new ClaudeCodeSessionReader();
        List<Path> discovered = reader.discoverSessionFiles(projectDir, 7, 10);

        assertThat(discovered).containsExactlyInAnyOrder(s1, s2);
        assertThat(discovered).doesNotContain(ignoredTxt);
    }
}
