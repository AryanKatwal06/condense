package com.condense.filter.stage;

import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoTestSummaryStageAdversarialTest {

    private final GoTestSummaryStage stage = GoTestSummaryStage.INSTANCE;

    @Test
    @DisplayName("Plain-text parallel tests preserve distinct diagnostics without cross-test contamination")
    void plainTextConcurrentTests_preservePerTestDiagnosticsWithoutMisattribution() {
        String plain = """
            === RUN   TestParallelAlpha
            === PAUSE TestParallelAlpha
            === RUN   TestParallelBeta
            === PAUSE TestParallelBeta
            === CONT  TestParallelAlpha
                alpha_test.go:42: expected alpha computation 100, got 200
                alpha_test.go:43: alpha cache state was invalid
            === CONT  TestParallelBeta
                beta_test.go:88: database connection timed out after 30s
                beta_test.go:89: failed to ping postgres replica
            --- FAIL: TestParallelAlpha (0.05s)
            --- FAIL: TestParallelBeta (0.08s)
            FAIL
            exit status 1
            FAIL\texample.com/service/parallel\t0.15s
            """;

        FilterContext context = FilterContext.empty();
        StageResult result = stage.process(plain, context);
        String output = result.output();

        // Verification 1: Both failures are reported
        assertThat(output).contains("go test: 2 failure(s)");
        assertThat(output).contains("FAIL: TestParallelAlpha");
        assertThat(output).contains("FAIL: TestParallelBeta");

        // Verification 2: TestParallelAlpha has only Alpha's diagnostics
        assertThat(output).contains("alpha_test.go:42: expected alpha computation 100, got 200");
        assertThat(output).contains("alpha_test.go:43: alpha cache state was invalid");

        // Verification 3: TestParallelBeta has only Beta's diagnostics
        assertThat(output).contains("beta_test.go:88: database connection timed out after 30s");
        assertThat(output).contains("beta_test.go:89: failed to ping postgres replica");

        // Verification 4: No cross-contamination between tests
        int alphaIndex = output.indexOf("FAIL: TestParallelAlpha");
        int betaIndex = output.indexOf("FAIL: TestParallelBeta");
        assertThat(alphaIndex).isLessThan(betaIndex);

        String alphaSection = output.substring(alphaIndex, betaIndex);
        String betaSection = output.substring(betaIndex);

        assertThat(alphaSection)
            .as("Alpha section must not contain Beta's errors")
            .doesNotContain("beta_test.go")
            .doesNotContain("database connection timed out");

        assertThat(betaSection)
            .as("Beta section must not contain Alpha's errors")
            .doesNotContain("alpha_test.go")
            .doesNotContain("expected alpha computation");

        // Verification 5: Structured IR emitted
        assertThat(context.documentBuilder().isPopulated()).isTrue();
        Document doc = context.documentBuilder().build("go test", "go test", 1, true, null);
        assertThat(doc.kind()).isEqualTo(Document.DocumentKind.TEST);
        Document.TestDocument testDoc = (Document.TestDocument) doc.document();
        assertThat(testDoc.failed()).isEqualTo(2);
        assertThat(testDoc.cases()).hasSize(2);
        assertThat(testDoc.cases().get(0).name()).isEqualTo("TestParallelAlpha");
        assertThat(testDoc.cases().get(0).detail()).contains("expected alpha computation");
        assertThat(testDoc.cases().get(1).name()).isEqualTo("TestParallelBeta");
        assertThat(testDoc.cases().get(1).detail()).contains("database connection timed out");
    }

    @Test
    @DisplayName("JSON test stream with 50 passes followed by package panic preserves package failure and diagnostics")
    void jsonStream_manyPassesFollowedByPanic_preservesFailureAndDiagnostics() {
        StringBuilder jsonInput = new StringBuilder();

        // 50 passing tests
        for (int i = 1; i <= 50; i++) {
            String testName = "TestPassUnit_" + i;
            jsonInput.append("{\"Action\":\"run\",\"Package\":\"example.com/billing\",\"Test\":\"").append(testName).append("\"}\n");
            jsonInput.append("{\"Action\":\"output\",\"Package\":\"example.com/billing\",\"Test\":\"").append(testName).append("\",\"Output\":\"=== RUN   ").append(testName).append("\\n\"}\n");
            jsonInput.append("{\"Action\":\"pass\",\"Package\":\"example.com/billing\",\"Test\":\"").append(testName).append("\",\"Elapsed\":0.001}\n");
        }

        // Package panic after the 50 passes
        jsonInput.append("{\"Action\":\"output\",\"Package\":\"example.com/billing\",\"Output\":\"panic: fatal nil pointer dereference in finalizeBilling\\n\"}\n");
        jsonInput.append("{\"Action\":\"output\",\"Package\":\"example.com/billing\",\"Output\":\"goroutine 1 [running]:\\n\"}\n");
        jsonInput.append("{\"Action\":\"output\",\"Package\":\"example.com/billing\",\"Output\":\"example.com/billing.finalizeBilling(0x0)\\n\"}\n");
        jsonInput.append("{\"Action\":\"output\",\"Package\":\"example.com/billing\",\"Output\":\"    /src/billing/finalize.go:120 +0x45\\n\"}\n");
        jsonInput.append("{\"Action\":\"output\",\"Package\":\"example.com/billing\",\"Output\":\"FAIL\\texample.com/billing\\t0.540s\\n\"}\n");
        jsonInput.append("{\"Action\":\"fail\",\"Package\":\"example.com/billing\",\"Elapsed\":0.54}\n");

        FilterContext context = FilterContext.empty();
        StageResult result = stage.process(jsonInput.toString(), context);
        String output = result.output();

        // Verification 1: Panic failure is visibly reported
        assertThat(output).contains("go test: 1 failure(s)");
        assertThat(output).contains("FAIL: [panic] example.com/billing");

        // Verification 2: Panic diagnostics are fully preserved
        assertThat(output).contains("panic: fatal nil pointer dereference in finalizeBilling");
        assertThat(output).contains("goroutine 1 [running]:");
        assertThat(output).contains("example.com/billing.finalizeBilling(0x0)");

        // Verification 3: Counts accurately report passed and failed
        assertThat(output).contains("passed: 50 | failed: 1");

        // Verification 4: Structured IR TestDocument is emitted
        assertThat(context.documentBuilder().isPopulated()).isTrue();
        Document doc = context.documentBuilder().build("go test", "go test", 1, true, null);
        assertThat(doc.kind()).isEqualTo(Document.DocumentKind.TEST);
        Document.TestDocument testDoc = (Document.TestDocument) doc.document();
        assertThat(testDoc.passed()).isEqualTo(50);
        assertThat(testDoc.failed()).isEqualTo(1);
        assertThat(testDoc.cases()).hasSize(1);
        assertThat(testDoc.cases().get(0).name()).contains("example.com/billing");
        assertThat(testDoc.cases().get(0).detail()).contains("panic: fatal nil pointer dereference");
    }
}
