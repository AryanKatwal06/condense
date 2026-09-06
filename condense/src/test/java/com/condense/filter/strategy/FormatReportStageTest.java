package com.condense.filter.strategy;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FormatReportStageTest {

    @TempDir
    Path tempDir;

    @Test
    void typicalReportKeepsDiagnosticIds() throws Exception {
        Path report = copy("format-report.json");
        StageResult result = FormatReportStage.INSTANCE.process("raw", context(List.of(report)));
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).contains("IDE0055");
        assertThat(result.output()).contains("IDE0005");
        assertThat(result.output()).contains("whitespace");
    }

    @Test
    void emptyArrayIsOk() {
        StageResult result = FormatReportStage.INSTANCE.process("[]", FilterContext.empty());
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).isEqualTo("dotnet-format: ok");
    }

    @Test
    void malformedObjectFallsThrough() throws Exception {
        Path report = copy("format-malformed.json");
        StageResult result = FormatReportStage.INSTANCE.process("Formatted 2 files.", context(List.of(report)));
        assertThat(result.shortCircuit()).isFalse();
        assertThat(result.output()).isEqualTo("Formatted 2 files.");
    }

    @Test
    void hugeJsonFallsThrough() {
        String huge = "[" + " ".repeat(FormatReportStage.MAX_BYTES + 8) + "]";
        StageResult result = FormatReportStage.INSTANCE.process(huge, FilterContext.empty());
        assertThat(result.shortCircuit()).isFalse();
    }

    private Path copy(String name) throws Exception {
        Path dest = tempDir.resolve(name);
        try (var in = FormatReportStageTest.class.getResourceAsStream("/fixtures/dotnet-format/" + name)) {
            assertThat(in).as(name).isNotNull();
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    private static FilterContext context(List<Path> artifacts) {
        return FilterContext.of(
            "dotnet format",
            new ExecutionResult(1, "Formatted 2 files.\n", "", 8L).withArtifacts(artifacts),
            CondenseConfig.defaults(),
            0,
            false);
    }
}
