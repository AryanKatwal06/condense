package com.condense.filter.strategy;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterIncident;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrxReportStageTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyXmlnsKeepsFailedTestsAndStacks() throws Exception {
        Path trx = copy("trx-failed.xml");
        StageResult result = TrxReportStage.INSTANCE.process("Failed!", context(List.of(trx)));
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).contains("TestInvoiceTotal");
        assertThat(result.output()).contains("TestAuthSession");
        assertThat(result.output()).doesNotContain("TestOk01");
    }

    @Test
    void namespacedUnicodeNameSurvives() throws Exception {
        Path trx = copy("trx-namespaced.xml");
        StageResult result = TrxReportStage.INSTANCE.process("raw", context(List.of(trx)));
        assertThat(result.output()).contains("测试发票");
        assertThat(result.output()).contains("发票");
    }

    @Test
    void multiFileMergeAddsFailures() throws Exception {
        Path first = copy("trx-failed.xml");
        Path second = copy("trx-second.xml");
        StageResult result = TrxReportStage.INSTANCE.process("raw", context(List.of(first, second)));
        assertThat(result.output()).contains("TestInvoiceTotal");
        assertThat(result.output()).contains("TestMerged");
    }

    @Test
    void xxeDoesNotLeakSecret() throws Exception {
        Path trx = copy("trx-xxe.xml");
        FilterContext ctx = context(List.of(trx));
        StageResult result = TrxReportStage.INSTANCE.process("Failed TestInvoiceTotal\n", ctx);
        assertThat(result.output()).doesNotContain("[fonts]");
        assertThat(result.output()).doesNotContain("for 16-bit app support");
        assertThat(ctx.incidents()).extracting(FilterIncident::kind)
            .contains(FilterIncident.KIND_TRX_XXE);
    }

    @Test
    void truncatedKeepsLastGoodOrRaw() throws Exception {
        Path trx = copy("trx-truncated.xml");
        FilterContext ctx = context(List.of(trx));
        StageResult result = TrxReportStage.INSTANCE.process("Failed!", ctx);
        if (result.shortCircuit()) {
            assertThat(result.output()).contains("TestKept");
        } else {
            assertThat(result.output()).isEqualTo("Failed!");
        }
    }

    @Test
    void missingTrxFallsThrough() {
        FilterContext ctx = FilterContext.of(
            "dotnet test",
            new ExecutionResult(1, "  Failed TestInvoiceTotal\nFailed!\n", "", 4L),
            CondenseConfig.defaults(),
            0,
            false);
        StageResult result = TrxReportStage.INSTANCE.process(ctx.result().readStdout(), ctx);
        assertThat(result.shortCircuit()).isFalse();
        assertThat(result.output()).contains("TestInvoiceTotal");
    }

    private Path copy(String name) throws Exception {
        String destName = name.endsWith(".xml") ? name.substring(0, name.length() - 4) + ".trx" : name;
        Path dest = tempDir.resolve(destName);
        try (var in = TrxReportStageTest.class.getResourceAsStream("/fixtures/dotnet-test/" + name)) {
            assertThat(in).as(name).isNotNull();
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    private static FilterContext context(List<Path> artifacts) {
        return FilterContext.of(
            "dotnet test",
            new ExecutionResult(1, "Failed!\n", "", 8L).withArtifacts(artifacts),
            CondenseConfig.defaults(),
            0,
            false);
    }
}
