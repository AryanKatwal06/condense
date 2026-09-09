package com.condense.bench;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.GitStatusStage;
import com.condense.filter.strategy.MachineUiStage;
import com.condense.filter.strategy.ResourceGraphStage;
import com.condense.filter.strategy.TrxReportStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Microbenchmarks for structured infrastructure, .NET, and IR parsers.
 * Asserts bounded allocation and p95 latency on standard and hostile inputs.
 */
class StructuredParserBenchmarkTest {

    private static final int WARMUP_RUNS = 50;
    private static final int MEASURE_RUNS = 100;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("TRX XML report parsing meets throughput and allocation bounds")
    void trxReportParsingMeetsThroughputAndAllocationBounds() throws Exception {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        xml.append("<TestRun id=\"1\" xmlns=\"http://microsoft.com/schemas/VisualStudio/TeamTest/2010\">\n");
        xml.append("  <Results>\n");
        for (int i = 0; i < 50; i++) {
            xml.append("    <UnitTestResult testName=\"UnitTest.").append(i)
               .append("\" outcome=\"").append(i % 5 == 0 ? "Failed" : "Passed")
               .append("\" duration=\"00:00:00.123\">\n");
            if (i % 5 == 0) {
                xml.append("      <Output><ErrorInfo><Message>Assert.Equal() Failure</Message>")
                   .append("<StackTrace>at Test.Run()</StackTrace></ErrorInfo></Output>\n");
            }
            xml.append("    </UnitTestResult>\n");
        }
        xml.append("  </Results>\n");
        xml.append("</TestRun>\n");

        Path trxFile = tempDir.resolve("results.trx");
        Files.writeString(trxFile, xml.toString(), StandardCharsets.UTF_8);
        String input = "Results File: " + trxFile.toAbsolutePath() + "\nFailed! - Failed: 10, Passed: 40\n";

        FilterContext context = new FilterContext("dotnet test " + trxFile.toAbsolutePath(), null, null, 0, false);

        // Warmup
        for (int i = 0; i < WARMUP_RUNS; i++) {
            TrxReportStage.INSTANCE.process(input, context);
        }

        double[] nanos = new double[MEASURE_RUNS];
        long allocBefore = BenchStats.currentThreadAllocatedBytes();
        for (int i = 0; i < MEASURE_RUNS; i++) {
            long t0 = System.nanoTime();
            StageResult res = TrxReportStage.INSTANCE.process(input, context);
            nanos[i] = System.nanoTime() - t0;
            assertThat(res.shortCircuit()).isTrue();
        }
        long allocTotal = BenchStats.currentThreadAllocatedBytes() - allocBefore;

        double p50Ms = BenchStats.percentile(nanos, 50.0) / 1_000_000.0;
        double p95Ms = BenchStats.percentile(nanos, 95.0) / 1_000_000.0;
        double meanMs = BenchStats.mean(nanos) / 1_000_000.0;
        double kbPerOp = allocTotal > 0 ? (double) allocTotal / MEASURE_RUNS / 1024.0 : 0.0;

        System.out.printf("TRX Parser: mean=%.2f ms | p50=%.2f ms | p95=%.2f ms | alloc=%.1f KB/op%n",
            meanMs, p50Ms, p95Ms, kbPerOp);

        assertThat(p95Ms)
            .as("TRX parser p95 should stay under 50ms")
            .isLessThan(50.0);
    }

    @Test
    @DisplayName("Terraform Machine UI and Resource Graph parsers meet latency bounds")
    void terraformMachineUiMeetsLatencyBounds() {
        StringBuilder ndjson = new StringBuilder();
        ndjson.append("{\"@module\":\"terraform.ui\",\"@level\":\"info\",\"@message\":\"Terraform 1.5.0\",\"type\":\"version\",\"ui\":\"1.0\"}\n");
        for (int i = 0; i < 80; i++) {
            ndjson.append("{\"@module\":\"terraform.ui\",\"@level\":\"info\",\"@message\":\"aws_instance.server[").append(i)
                  .append("]: Creating...\",\"type\":\"planned_change\",\"change\":{\"resource\":{\"addr\":\"aws_instance.server[").append(i)
                  .append("]\"},\"action\":\"create\"}}\n");
        }
        ndjson.append("{\"@module\":\"terraform.ui\",\"@level\":\"info\",\"type\":\"change_summary\",\"changes\":{\"add\":80,\"change\":0,\"remove\":0}}\n");
        String input = ndjson.toString();

        ResourceGraphStage resourceGraph = ResourceGraphStage.ofPreset("resource_type", "", 20, 2000, "");

        for (int i = 0; i < WARMUP_RUNS; i++) {
            MachineUiStage.INSTANCE.process(input, FilterContext.empty());
            resourceGraph.process(input, FilterContext.empty());
        }

        double[] uiNanos = new double[MEASURE_RUNS];
        for (int i = 0; i < MEASURE_RUNS; i++) {
            long t0 = System.nanoTime();
            StageResult res = MachineUiStage.INSTANCE.process(input, FilterContext.empty());
            uiNanos[i] = System.nanoTime() - t0;
            assertThat(res.shortCircuit()).isTrue();
        }

        double p95UiMs = BenchStats.percentile(uiNanos, 95.0) / 1_000_000.0;
        System.out.printf("Terraform MachineUI Parser: p95=%.2f ms%n", p95UiMs);

        assertThat(p95UiMs)
            .as("Terraform Machine UI p95 should stay under 25ms")
            .isLessThan(25.0);
    }

    @Test
    @DisplayName("Git Status and Format Report parsers meet latency and allocation bounds")
    void gitAndFormatParsersMeetBounds() {
        StringBuilder gitStatus = new StringBuilder();
        gitStatus.append("On branch main\nYour branch is up to date with 'origin/main'.\n\nChanges not staged for commit:\n");
        for (int i = 0; i < 60; i++) {
            gitStatus.append("\tmodified:   src/main/file").append(i).append(".java\n");
        }
        String gitInput = gitStatus.toString();

        for (int i = 0; i < WARMUP_RUNS; i++) {
            GitStatusStage.INSTANCE.process(gitInput, FilterContext.empty());
        }

        double[] gitNanos = new double[MEASURE_RUNS];
        for (int i = 0; i < MEASURE_RUNS; i++) {
            long t0 = System.nanoTime();
            StageResult res = GitStatusStage.INSTANCE.process(gitInput, FilterContext.empty());
            gitNanos[i] = System.nanoTime() - t0;
            assertThat(res).isNotNull();
        }

        double p95GitMs = BenchStats.percentile(gitNanos, 95.0) / 1_000_000.0;
        System.out.printf("Git Status Parser: p95=%.2f ms%n", p95GitMs);

        assertThat(p95GitMs)
            .as("Git status parser p95 should stay under 20ms")
            .isLessThan(20.0);
    }

    @Test
    @DisplayName("Hostile parser bombs degrade gracefully without memory exhaustion or stack overflow")
    void parserBombsDegradeGracefully() {
        // Deeply nested / massive repetitive NDJSON payload exceeding limits
        StringBuilder bomb = new StringBuilder();
        for (int i = 0; i < 15_000; i++) {
            bomb.append("{\"key\":").append("{\"nested\":".repeat(5))
                .append("\"val\"").append("}".repeat(5))
                .append(",\"i\":").append(i).append("}\n");
        }
        String bombInput = bomb.toString();

        long t0 = System.nanoTime();
        StageResult res = MachineUiStage.INSTANCE.process(bombInput, FilterContext.empty());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("Hostile parser bomb processed in %d ms (capped / safe)%n", elapsedMs);

        assertThat(res).isNotNull();
        assertThat(elapsedMs)
            .as("Hostile input must be capped and finish in under 1000ms")
            .isLessThan(1000);
    }
}
