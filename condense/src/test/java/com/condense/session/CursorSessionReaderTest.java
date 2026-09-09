package com.condense.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CursorSessionReaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Parses Cursor session object with commands list and redacts secrets")
    void parsesCursorJsonObject() throws IOException {
        String json = """
            {
              "sessionId": "cursor-sess-99",
              "workspace": "/home/dev/myapp",
              "commands": [
                {
                  "command": "git pull origin main",
                  "exitCode": 0,
                  "durationMillis": 850,
                  "output": "Already up to date.",
                  "timestamp": "2026-09-09T12:00:00Z"
                },
                {
                  "command": "npm test --password=secret9988",
                  "exitCode": 1,
                  "durationMillis": 2400,
                  "output": "1 failing test",
                  "timestamp": "2026-09-09T12:01:00Z"
                }
              ]
            }
            """;

        Path file = tempDir.resolve("cursor_run.json");
        Files.writeString(file, json);

        CursorSessionReader reader = new CursorSessionReader();
        SessionRecord record = reader.parseSession(file, 1024 * 1024);

        assertThat(record.sessionId()).isEqualTo("cursor-sess-99");
        assertThat(record.agentFormat()).isEqualTo(AgentTranscriptFormat.CURSOR);
        assertThat(record.workspaceRoot()).isEqualTo("/home/dev/myapp");
        assertThat(record.events()).hasSize(2);

        SessionRecord.SessionEvent evt1 = record.events().get(0);
        assertThat(evt1.command()).isEqualTo("git pull origin main");
        assertThat(evt1.exitCode()).isEqualTo(0);

        SessionRecord.SessionEvent evt2 = record.events().get(1);
        assertThat(evt2.command()).contains("--password=[REDACTED_SECRET]");
        assertThat(evt2.command()).doesNotContain("secret9988");
        assertThat(evt2.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("Parses Cursor top-level array format")
    void parsesCursorJsonArray() throws IOException {
        String json = """
            [
              {
                "cmd": "docker ps",
                "exit_code": 0,
                "output": "CONTAINER ID IMAGE...",
                "timestamp": "2026-09-09T12:05:00Z"
              }
            ]
            """;

        Path file = tempDir.resolve("cursor_arr.json");
        Files.writeString(file, json);

        CursorSessionReader reader = new CursorSessionReader();
        SessionRecord record = reader.parseSession(file, 1024 * 1024);

        assertThat(record.events()).hasSize(1);
        assertThat(record.events().get(0).command()).isEqualTo("docker ps");
    }
}
