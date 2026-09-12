package com.condense.ir;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.stage.CargoTestSummaryStage;
import com.condense.filter.stage.DockerBuildSummaryStage;
import com.condense.filter.stage.GoTestSummaryStage;
import com.condense.filter.stage.JestSummaryStage;
import com.condense.filter.stage.PlaywrightSummaryStage;
import com.condense.filter.stage.VitestSummaryStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerIrCoverageTest {

    @Test
    @DisplayName("VitestSummaryStage populates TestDocument IR")
    void vitestEmitsTestDocument() {
        String input = """
            RUN  v1.0.0 /workspace/project
            ❯ src/math.test.ts (2 tests | 1 failed)
            ✓ adds numbers correctly 5ms
            × divides by zero 2ms
            → Expected 0, received Infinity
                at tests/math.test.ts:15:20
            Tests  1 failed | 1 passed (2)
            """;
        FilterContext context = new FilterContext("vitest", new ExecutionResult(1, input, "", 50L), CondenseConfig.defaults(), 0, false);
        VitestSummaryStage.INSTANCE.process(input, context);

        assertThat(context.documentBuilder().isPopulated()).isTrue();
        Document doc = context.documentBuilder().build("vitest", "vitest", 1, true, null);
        assertThat(doc.kind()).isEqualTo(Document.DocumentKind.TEST);
        Document.TestDocument testDoc = (Document.TestDocument) doc.document();
        assertThat(testDoc.tool()).isEqualTo("vitest");
        assertThat(testDoc.failed()).isEqualTo(1);
        assertThat(testDoc.passed()).isEqualTo(1);
        assertThat(testDoc.cases()).hasSize(1);
        assertThat(testDoc.cases().get(0).name()).contains("divides by zero");
        assertThat(testDoc.cases().get(0).stack()).contains("tests/math.test.ts:15:20");
    }

    @Test
    @DisplayName("PlaywrightSummaryStage populates TestDocument IR")
    void playwrightEmitsTestDocument() {
        String input = """
              1) auth.spec.ts:12:5 › should login with MFA ──────────────────────
                Error: Timeout 5000ms exceeded waiting for locator('#mfa-input')
                at tests/auth.spec.ts:15:10
              1 failed (6.2s)
            """;
        FilterContext context = new FilterContext("playwright test", new ExecutionResult(1, input, "", 100L), CondenseConfig.defaults(), 0, false);
        PlaywrightSummaryStage.INSTANCE.process(input, context);

        assertThat(context.documentBuilder().isPopulated()).isTrue();
        Document doc = context.documentBuilder().build("playwright test", "playwright", 1, true, null);
        assertThat(doc.kind()).isEqualTo(Document.DocumentKind.TEST);
        Document.TestDocument testDoc = (Document.TestDocument) doc.document();
        assertThat(testDoc.tool()).isEqualTo("playwright");
        assertThat(testDoc.failed()).isEqualTo(1);
        assertThat(testDoc.cases()).hasSize(1);
        assertThat(testDoc.cases().get(0).name()).contains("should login with MFA");
        assertThat(testDoc.cases().get(0).stack()).contains("tests/auth.spec.ts:15:10");
    }

    @Test
    @DisplayName("CargoTestSummaryStage populates TestDocument IR")
    void cargoTestEmitsTestDocument() {
        String input = """
            running 2 tests
            test test_success ... ok
            test test_failure ... FAILED
            test result: FAILED. 1 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.05s
            """;
        FilterContext context = new FilterContext("cargo test", new ExecutionResult(1, input, "", 80L), CondenseConfig.defaults(), 0, false);
        CargoTestSummaryStage.INSTANCE.process(input, context);

        assertThat(context.documentBuilder().isPopulated()).isTrue();
        Document doc = context.documentBuilder().build("cargo test", "cargo-test", 1, true, null);
        assertThat(doc.kind()).isEqualTo(Document.DocumentKind.TEST);
        Document.TestDocument testDoc = (Document.TestDocument) doc.document();
        assertThat(testDoc.tool()).isEqualTo("cargo test");
        assertThat(testDoc.passed()).isEqualTo(1);
        assertThat(testDoc.failed()).isEqualTo(1);
        assertThat(testDoc.cases()).hasSize(1);
        assertThat(testDoc.cases().get(0).name()).isEqualTo("test_failure");
    }

    @Test
    @DisplayName("DockerBuildSummaryStage populates BuildDocument IR")
    void dockerBuildEmitsBuildDocument() {
        String input = """
            #1 [internal] load build definition from Dockerfile
            #1 DONE 0.0s
            #2 [internal] load .dockerignore
            #2 DONE 0.0s
            Successfully built 7f8a9b0c1d2e
            Successfully tagged my-app:latest
            """;
        FilterContext context = new FilterContext("docker build .", new ExecutionResult(0, input, "", 500L), CondenseConfig.defaults(), 0, false);
        DockerBuildSummaryStage.INSTANCE.process(input, context);

        assertThat(context.documentBuilder().isPopulated()).isTrue();
        Document doc = context.documentBuilder().build("docker build .", "docker-build", 0, true, null);
        assertThat(doc.kind()).isEqualTo(Document.DocumentKind.BUILD);
        Document.BuildDocument buildDoc = (Document.BuildDocument) doc.document();
        assertThat(buildDoc.tool()).isEqualTo("docker build");
        assertThat(buildDoc.status()).isEqualTo("SUCCESS");
        assertThat(buildDoc.errors()).isEqualTo(0);
        assertThat(buildDoc.summaryLines()).contains("image: 7f8a9b0c1d2e");
        assertThat(buildDoc.summaryLines()).contains("tag: my-app:latest");
    }
}
