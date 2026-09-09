package com.condense.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Executes session analyze with text format and handles empty transcripts")
    void executesAnalyzeTextFormat() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);

        SessionCommand.AnalyzeCommand cmd = new SessionCommand.AnalyzeCommand(
            new SessionIntelligenceService(),
            ps
        );
        cmd.path = tempDir;
        cmd.agent = "all";
        cmd.format = "text";

        int exit = cmd.call();
        assertThat(exit).isEqualTo(0);

        String output = baos.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Condense Session Intelligence");
        assertThat(output).contains("Sessions analyzed:         0");
    }

    @Test
    @DisplayName("Executes session analyze with json format and export option")
    void executesAnalyzeJsonFormatAndExport() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);

        Path exportFile = tempDir.resolve("report.json");

        SessionCommand.AnalyzeCommand cmd = new SessionCommand.AnalyzeCommand(
            new SessionIntelligenceService(),
            ps
        );
        cmd.path = tempDir;
        cmd.agent = "claude";
        cmd.format = "json";
        cmd.output = exportFile;

        int exit = cmd.call();
        assertThat(exit).isEqualTo(0);

        String output = baos.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"total_sessions\" : 0");
        assertThat(Files.exists(exportFile)).isTrue();
        assertThat(Files.readString(exportFile)).contains("\"total_sessions\" : 0");
    }

    @Test
    @DisplayName("Executes session analyze with summary format")
    void executesAnalyzeSummaryFormat() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);

        SessionCommand.AnalyzeCommand cmd = new SessionCommand.AnalyzeCommand(
            new SessionIntelligenceService(),
            ps
        );
        cmd.path = tempDir;
        cmd.agent = "cursor";
        cmd.format = "summary";

        int exit = cmd.call();
        assertThat(exit).isEqualTo(0);

        String output = baos.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Analyzed 0 sessions");
    }

    @Test
    @DisplayName("Returns non-zero exit on invalid agent format")
    void failsOnInvalidAgentFormat() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);

        SessionCommand.AnalyzeCommand cmd = new SessionCommand.AnalyzeCommand(
            new SessionIntelligenceService(),
            ps
        );
        cmd.agent = "nonexistent-agent";

        int exit = cmd.call();
        assertThat(exit).isEqualTo(1);

        String output = baos.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Unknown agent format: nonexistent-agent");
    }
}
