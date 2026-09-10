package com.condense.session;

import com.condense.core.Mappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FailureExportAnalyzerTest {

    @Test
    @DisplayName("Empty directory returns zero reports with clean summary")
    void analyzeEmptyDirectoryReturnsZeroReports(@TempDir Path tempDir) {
        FailureExportAnalyzer analyzer = new FailureExportAnalyzer();
        FailureExportAnalyzer.AnalysisSummary summary = analyzer.analyze(tempDir);

        assertThat(summary.totalReports()).isEqualTo(0);
        assertThat(summary.unparseableFiles()).isEqualTo(0);
        assertThat(summary.stageBreakdown()).isEmpty();
        assertThat(summary.renderText()).contains("No valid failure reports found in target.");
    }

    @Test
    @DisplayName("Multiple exported failure reports are correctly aggregated across dimensions")
    void analyzeMultipleExportPayloadsAggregatesCorrectly(@TempDir Path tempDir) throws Exception {
        FailureReportPayload p1 = new FailureReportPayload(
            "1.0.1", "Linux", "x86_64", "PROXY_EXECUTION", "COMPILATION_ERROR", 1, 250L, 2048L);
        FailureReportPayload p2 = new FailureReportPayload(
            "1.0.1", "Linux", "x86_64", "PROXY_EXECUTION", "TEST_FAILURE", 1, 500L, 1024L);
        FailureReportPayload p3 = new FailureReportPayload(
            "1.0.1", "Linux", "x86_64", "STREAM_PIPELINE", "TIMEOUT", -1, 6000L, 512L);

        Files.write(tempDir.resolve("report-1.json"), Mappers.JSON.writeValueAsBytes(p1));
        Files.write(tempDir.resolve("report-2.json"), Mappers.JSON.writeValueAsBytes(p2));
        Files.write(tempDir.resolve("report-3.json"), Mappers.JSON.writeValueAsBytes(p3));

        FailureExportAnalyzer analyzer = new FailureExportAnalyzer();
        FailureExportAnalyzer.AnalysisSummary summary = analyzer.analyze(tempDir);

        assertThat(summary.totalReports()).isEqualTo(3);
        assertThat(summary.unparseableFiles()).isEqualTo(0);

        assertThat(summary.stageBreakdown())
            .containsEntry("PROXY_EXECUTION", 2)
            .containsEntry("STREAM_PIPELINE", 1);

        assertThat(summary.categoryBreakdown())
            .containsEntry("COMPILATION_ERROR", 1)
            .containsEntry("TEST_FAILURE", 1)
            .containsEntry("TIMEOUT", 1);

        assertThat(summary.exitCodeBreakdown())
            .containsEntry(1, 2)
            .containsEntry(-1, 1);

        String text = summary.renderText();
        assertThat(text).contains("Total Reports Analyzed: 3");
        assertThat(text).contains("PROXY_EXECUTION");
        assertThat(text).contains("COMPILATION_ERROR");
        assertThat(text).contains("TIMEOUT");
    }

    @Test
    @DisplayName("Corrupted and malformed JSON files fail open without crashing")
    void analyzeToleratesCorruptedJsonFilesFailOpen(@TempDir Path tempDir) throws Exception {
        FailureReportPayload valid = new FailureReportPayload(
            "1.0.1", "macOS", "aarch64", "EXECUTION", "OOM", 137, 100L, 5000L);
        Files.write(tempDir.resolve("valid.json"), Mappers.JSON.writeValueAsBytes(valid));
        Files.writeString(tempDir.resolve("corrupt.json"), "{ invalid json syntax ");

        FailureExportAnalyzer analyzer = new FailureExportAnalyzer();
        FailureExportAnalyzer.AnalysisSummary summary = analyzer.analyze(tempDir);

        assertThat(summary.totalReports()).isEqualTo(1);
        assertThat(summary.unparseableFiles()).isEqualTo(1);
        assertThat(summary.categoryBreakdown()).containsEntry("OOM", 1);
        assertThat(summary.renderText()).contains("1 invalid/skipped files");
    }

    @Test
    @DisplayName("ReportCommand CLI integrates --analyze option seamlessly")
    void reportCommandIntegratesAnalyzeOption(@TempDir Path tempDir) throws Exception {
        FailureReportPayload p = new FailureReportPayload(
            "1.0.1", "Windows", "amd64", "HOOK", "PERMISSION_DENIED", 5, 20L, 100L);
        Files.write(tempDir.resolve("report.json"), Mappers.JSON.writeValueAsBytes(p));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8);

        ReportCommand cmd = new ReportCommand(new TelemetryService(), printStream);
        CommandLine cl = new CommandLine(cmd);
        int exitCode = cl.execute("--analyze", tempDir.toString());

        assertThat(exitCode).isEqualTo(0);
        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("=== Condense Failure Export Analysis ===");
        assertThat(output).contains("Total Reports Analyzed: 1");
        assertThat(output).contains("PERMISSION_DENIED");
    }

    @Test
    @DisplayName("Single file path can be analyzed directly")
    void analyzeSingleFileDirectly(@TempDir Path tempDir) throws Exception {
        FailureReportPayload p = new FailureReportPayload(
            "1.0.1", "Linux", "x86_64", "CORE", "SIGKILL", 9, 10L, 50L);
        Path singleFile = tempDir.resolve("single.json");
        Files.write(singleFile, Mappers.JSON.writeValueAsBytes(p));

        FailureExportAnalyzer analyzer = new FailureExportAnalyzer();
        FailureExportAnalyzer.AnalysisSummary summary = analyzer.analyze(singleFile);

        assertThat(summary.totalReports()).isEqualTo(1);
        assertThat(summary.categoryBreakdown()).containsEntry("SIGKILL", 1);
    }
}
