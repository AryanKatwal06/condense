package com.condense.filter.strategy;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterIncident;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MsbuildBinlogStageTest {

    @TempDir
    Path tempDir;

    @Test
    void v18ErrorAndWarningFieldsSurvive() throws Exception {
        Path binlog = BinlogFixtureWriter.writeTypical(tempDir.resolve("msbuild.binlog"));
        FilterContext context = context(List.of(binlog), "dotnet build");
        StageResult result = MsbuildBinlogStage.INSTANCE.process("Build FAILED.\n", context);
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).contains("CS0029");
        assertThat(result.output()).contains("Cannot implicitly convert type");
        assertThat(result.output()).contains("CS0219");
        assertThat(context.documentBuilder().isPopulated()).isTrue();
        Document built = context.documentBuilder().build("dotnet build", "dotnet-build", 1, true, null);
        Document.DiagnosticDocument payload = (Document.DiagnosticDocument) built.document();
        assertThat(payload.tool()).isEqualTo("msbuild");
        assertThat(payload.errors()).isEqualTo(1);
        assertThat(payload.warnings()).isEqualTo(1);
        assertThat(payload.findings().getFirst().file()).isEqualTo("Program.cs");
        assertThat(payload.findings().getFirst().line()).isEqualTo(12);
    }

    @Test
    void unknownRecordIsSkipped() throws Exception {
        Path binlog = tempDir.resolve("skip.binlog");
        Files.write(binlog, BinlogFixtureWriter.withUnknownRecord());
        FilterContext context = context(List.of(binlog), "dotnet build");
        StageResult result = MsbuildBinlogStage.INSTANCE.process("raw", context);
        assertThat(result.output()).contains("CS0117");
        assertThat(result.output()).contains("Save");
    }

    @Test
    void version17Continues() throws Exception {
        Path binlog = tempDir.resolve("old.binlog");
        Files.write(binlog, BinlogFixtureWriter.version(17, 17, List.of(
            new BinlogFixtureWriter.Event(MsbuildBinlogStage.KIND_ERROR, "CS0029", "a.cs", 1, "nope")
        )));
        FilterContext context = context(List.of(binlog), "dotnet build");
        StageResult result = MsbuildBinlogStage.INSTANCE.process("Build FAILED.\n", context);
        assertThat(result.shortCircuit()).isFalse();
        assertThat(result.output()).isEqualTo("Build FAILED.\n");
        assertThat(context.incidents()).extracting(FilterIncident::kind)
            .contains(FilterIncident.KIND_BINLOG_VERSION);
    }

    @Test
    void unsupportedHighVersionContinues() throws Exception {
        Path binlog = tempDir.resolve("future.binlog");
        Files.write(binlog, BinlogFixtureWriter.version(40, 30, List.of()));
        FilterContext context = context(List.of(binlog), "dotnet build");
        StageResult result = MsbuildBinlogStage.INSTANCE.process("human text", context);
        assertThat(result.shortCircuit()).isFalse();
        assertThat(result.output()).isEqualTo("human text");
    }

    @Test
    void missingArtifactFallsThroughToConsole() {
        FilterContext context = FilterContext.of(
            "dotnet build",
            new ExecutionResult(1, "error CS0029: fail\nBuild FAILED.\n", "", 4L),
            CondenseConfig.defaults(),
            0,
            false);
        StageResult result = MsbuildBinlogStage.INSTANCE.process(context.result().readStdout(), context);
        assertThat(result.shortCircuit()).isFalse();
        assertThat(result.output()).contains("CS0029");
    }

    @Test
    void gzipBombStaysBounded() {
        byte[] header = new byte[8];
        header[0] = 18;
        byte[] bomb = new byte[8 + 100];
        System.arraycopy(header, 0, bomb, 0, 8);
        // invalid gzip after header
        bomb[8] = (byte) 0x1f;
        bomb[9] = (byte) 0x8b;
        MsbuildBinlogStage.FileParse parsed = MsbuildBinlogStage.parseBytes(bomb, FilterContext.empty());
        assertThat(parsed == null || !parsed.supported || parsed.findings.isEmpty()).isTrue();
    }

    private static FilterContext context(List<Path> artifacts, String command) {
        ExecutionResult result = new ExecutionResult(1, "Build FAILED.\n", "", 8L)
            .withArtifacts(artifacts);
        return FilterContext.of(command, result, CondenseConfig.defaults(), 0, false);
    }
}
