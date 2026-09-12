package com.condense.filter;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.golang.GoTestFilter;
import com.condense.filter.golang.GolangciLintFilter;
import com.condense.filter.node.ESLintFilter;
import com.condense.filter.node.JestFilter;
import com.condense.filter.node.TscFilter;
import com.condense.filter.node.VitestFilter;
import com.condense.filter.pipeline.CatalogBackedFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enhancement Phase 7: Composed Pipeline Integration Tests with Real Fixtures.
 *
 * <p>Validates that domain filters running through the complete multi-stage pipeline
 * stack (pre-pipeline gating, stream selection, ANSI stripping, domain semantic reshaping,
 * deduplication, head/tail truncation, and document IR attachment) preserve all critical
 * failure signals on non-zero exit codes, achieve measurable token savings on clean runs,
 * and leak zero internal diagnostics.
 */
class ComposedPipelineIntegrationTest extends FilterTestSupport {

    private CondenseConfig config;

    @BeforeEach
    void setUp() {
        config = CondenseConfig.defaults();
    }

    // =========================================================================
    // JavaScript & TypeScript Ecosystem
    // =========================================================================

    @Test
    @DisplayName("Jest composed pipeline preserves assertion diffs, timeouts, and failure blocks while dropping passing suites")
    void testJestComposedPipeline_failurePreservesDiffAndStack() throws Exception {
        JestFilter filter = new JestFilter();
        String raw = fixture("jest", "typical");
        ExecutionResult exec = new ExecutionResult(1, raw, "", 450L);

        FilterResult result = filter.apply("jest", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense:");

        // Critical failure signals
        assertThat(result.output()).contains("Button.test.js");
        assertThat(result.output()).contains("auth.test.js");
        assertThat(result.output()).contains("expect(received).toBeTruthy()");
        assertThat(result.output()).contains("Received: false");
        assertThat(result.output()).contains("timeout of 5000ms exceeded");

        // Noise elimination
        assertThat(result.output()).doesNotContain("math.test.js");
        assertThat(result.output()).doesNotContain("string.test.js");
    }

    @Test
    @DisplayName("Jest composed pipeline compresses passing test suites with clean success summary")
    void testJestComposedPipeline_successCompresses() throws Exception {
        JestFilter filter = new JestFilter();
        String raw = fixture("jest", "passing");
        ExecutionResult exec = new ExecutionResult(0, raw, "", 120L);

        FilterResult result = filter.apply("jest", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense:");
        assertThat(result.output()).containsAnyOf("passed", "✓", "Ran all test suites.");
    }

    @Test
    @DisplayName("Vitest composed pipeline preserves failure blocks and assertion details")
    void testVitestComposedPipeline_failurePreservesAssertionsAndSummary() throws Exception {
        VitestFilter filter = new VitestFilter();
        String raw = fixture("vitest", "typical");
        ExecutionResult exec = new ExecutionResult(1, raw, "", 380L);

        FilterResult result = filter.apply("vitest", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical signals
        assertThat(result.output()).contains("divides by zero");
        assertThat(result.output()).contains("handles null input");
        assertThat(result.output()).contains("Expected 0, received Infinity");
        assertThat(result.output()).contains("Cannot read property 'length' of null");
        assertThat(result.output()).containsAnyOf("Tests", "2 failed");

        // Passing noise eliminated
        assertThat(result.output()).doesNotContain("✓ utils.test.ts");
    }

    @Test
    @DisplayName("Playwright composed pipeline retains failing spec locations while suppressing noisy browser action logs")
    void testPlaywrightComposedPipeline_failurePreservesFailingSpec() throws Exception {
        CatalogBackedFilter filter = new CatalogBackedFilter("playwright");
        String raw = fixture("playwright", "typical");
        ExecutionResult exec = new ExecutionResult(1, raw, "", 500L);

        FilterResult result = filter.apply("playwright test", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical failure signals preserved
        assertThat(result.output()).contains("invoice.spec.ts:12:5");
        assertThat(result.output()).contains("session.spec.ts:8:1");
        assertThat(result.output()).contains("2 failed");

        // Noise suppression
        assertThat(result.output()).doesNotContain("noise spec 1 (12ms)");
        assertThat(result.output()).doesNotContain("noise spec 10 (12ms)");
    }

    @Test
    @DisplayName("ESLint composed pipeline summarizes error and warning counts")
    void testEslintComposedPipeline_failurePreservesErrorsAndRules() throws Exception {
        ESLintFilter filter = new ESLintFilter();
        String raw = fixture("eslint", "typical");
        ExecutionResult exec = new ExecutionResult(1, raw, "", 210L);

        FilterResult result = filter.apply("eslint", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical diagnostic signals preserved
        assertThat(result.output()).contains("eslint:");
        assertThat(result.output()).contains("error(s)");
        assertThat(result.output()).contains("warning(s)");
    }

    @Test
    @DisplayName("TypeScript compiler composed pipeline summarizes error counts per source file")
    void testTscComposedPipeline_failurePreservesErrorCodeAndLine() throws Exception {
        TscFilter filter = new TscFilter();
        String raw = fixture("tsc", "typical");
        ExecutionResult exec = new ExecutionResult(1, raw, "", 300L);

        FilterResult result = filter.apply("tsc", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical diagnostic signals
        assertThat(result.output()).contains("tsc: 3 error(s)");
        assertThat(result.output()).contains("src/api/client.ts");
        assertThat(result.output()).contains("src/utils/format.ts");
    }

    // =========================================================================
    // Go Ecosystem
    // =========================================================================

    @Test
    @DisplayName("Go test json composed pipeline preserves test failure diagnostics and suppresses passing test events")
    void testGoTestJsonComposedPipeline_failurePreservesDiagnostics() throws Exception {
        GoTestFilter filter = new GoTestFilter();
        String raw = fixture("go-test", "json-typical");
        ExecutionResult exec = new ExecutionResult(1, raw, "", 400L);

        FilterResult result = filter.apply("go test -json ./...", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical failure signals
        assertThat(result.output()).contains("FAIL: TestMultiply");
        assertThat(result.output()).contains("FAIL: TestDivide");
        assertThat(result.output()).contains("failed: 2");

        // Raw json event lines stripped
        assertThat(result.output()).doesNotContain("{\"Action\":\"run\"");
        assertThat(result.output()).doesNotContain("{\"Action\":\"pass\"");
    }

    @Test
    @DisplayName("golangci-lint composed pipeline preserves issue categories and counts")
    void testGolangciLintComposedPipeline_failurePreservesLinterMessages() throws Exception {
        GolangciLintFilter filter = new GolangciLintFilter();
        String raw = fixture("golangci-lint", "typical");
        ExecutionResult exec = new ExecutionResult(1, raw, "", 250L);

        FilterResult result = filter.apply("golangci-lint run", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical signals preserved
        assertThat(result.output()).contains("golangci-lint: 5 issue(s)");
        assertThat(result.output()).contains("errcheck");
        assertThat(result.output()).contains("govet");
    }
}
