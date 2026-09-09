package com.condense.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WindsurfSessionReaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Parses Windsurf cascade steps format and masks sensitive data")
    void parsesWindsurfCascadeSteps() throws IOException {
        String json = """
            {
              "cascadeId": "cascade-7788",
              "directory": "/workspace/service",
              "steps": [
                {
                  "stepId": "s-1",
                  "command": "terraform plan -var 'api_key=AIzaSyD1234567890abcdef1234567890abcde'",
                  "exit_code": 0,
                  "durationMillis": 3100,
                  "output": "Plan: 2 to add, 0 to change, 0 to destroy.",
                  "timestamp": "2026-09-09T14:00:00Z"
                },
                {
                  "stepId": "s-2",
                  "command": "terraform apply -auto-approve",
                  "exit_code": 1,
                  "durationMillis": 1200,
                  "output": "Error: provider failed",
                  "timestamp": "2026-09-09T14:01:00Z"
                }
              ]
            }
            """;

        Path file = tempDir.resolve("cascade.json");
        Files.writeString(file, json);

        WindsurfSessionReader reader = new WindsurfSessionReader();
        SessionRecord record = reader.parseSession(file, 1024 * 1024);

        assertThat(record.sessionId()).isEqualTo("cascade-7788");
        assertThat(record.agentFormat()).isEqualTo(AgentTranscriptFormat.WINDSURF);
        assertThat(record.workspaceRoot()).isEqualTo("/workspace/service");
        assertThat(record.events()).hasSize(2);

        SessionRecord.SessionEvent step1 = record.events().get(0);
        assertThat(step1.command()).contains("[REDACTED_GOOGLE_KEY]");
        assertThat(step1.command()).doesNotContain("AIzaSyD");
        assertThat(step1.exitCode()).isEqualTo(0);

        SessionRecord.SessionEvent step2 = record.events().get(1);
        assertThat(step2.command()).isEqualTo("terraform apply -auto-approve");
        assertThat(step2.exitCode()).isEqualTo(1);
    }
}
