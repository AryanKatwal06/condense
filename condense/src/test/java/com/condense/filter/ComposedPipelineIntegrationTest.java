package com.condense.filter;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.build.GradleFilter;
import com.condense.filter.build.MakeFilter;
import com.condense.filter.build.MvnFilter;
import com.condense.filter.cargo.CargoClippyFilter;
import com.condense.filter.cargo.CargoTestFilter;
import com.condense.filter.cloud.DockerBuildFilter;
import com.condense.filter.cloud.KubectlFilter;
import com.condense.filter.git.GitDiffFilter;
import com.condense.filter.git.GitStatusFilter;
import com.condense.filter.golang.GoTestFilter;
import com.condense.filter.golang.GolangciLintFilter;
import com.condense.filter.node.ESLintFilter;
import com.condense.filter.node.JestFilter;
import com.condense.filter.node.TscFilter;
import com.condense.filter.node.VitestFilter;
import com.condense.filter.pipeline.CatalogBackedFilter;
import com.condense.filter.python.PytestFilter;
import com.condense.filter.python.RuffFilter;
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

    // =========================================================================
    // Rust Ecosystem
    // =========================================================================

    @Test
    @DisplayName("Cargo test composed pipeline preserves failed test names and panic details while stripping passing tests")
    void testCargoTestComposedPipeline_failurePreservesPanicsAndFailedNames() throws Exception {
        CargoTestFilter filter = new CargoTestFilter();
        String raw = fixture("cargo-test", "typical");
        ExecutionResult exec = new ExecutionResult(101, raw, "", 400L);

        FilterResult result = filter.apply("cargo test", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical signals
        assertThat(result.output()).contains("FAILED: tests::test_multiply");
        assertThat(result.output()).contains("FAILED: tests::test_modulo");
        assertThat(result.output()).contains("2 failure(s)");

        // Passing noise eliminated
        assertThat(result.output()).doesNotContain("test tests::test_add ... ok");
    }

    @Test
    @DisplayName("Cargo test composed pipeline compresses all-passing test suite")
    void testCargoTestComposedPipeline_successCompresses() throws Exception {
        CargoTestFilter filter = new CargoTestFilter();
        String raw = fixture("cargo-test", "passing");
        ExecutionResult exec = new ExecutionResult(0, raw, "", 120L);

        FilterResult result = filter.apply("cargo test", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");
        assertThat(result.output()).containsAnyOf("passed", "✓");
    }

    @Test
    @DisplayName("Cargo clippy composed pipeline preserves compiler and linter warning diagnostics")
    void testCargoClippyComposedPipeline_warningDiagnostics() throws Exception {
        CargoClippyFilter filter = new CargoClippyFilter();
        String raw = fixture("cargo-clippy", "typical");
        ExecutionResult exec = new ExecutionResult(0, raw, "", 300L);

        FilterResult result = filter.apply("cargo clippy", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical diagnostics
        assertThat(result.output()).contains("cargo clippy: 2 warning(s)");
        assertThat(result.output()).contains("unused_variables");
    }

    // =========================================================================
    // Python Ecosystem
    // =========================================================================

    @Test
    @DisplayName("Pytest composed pipeline preserves failing test names and assertion introspection while dropping passing tests")
    void testPytestComposedPipeline_failurePreservesFailuresAndSummary() throws Exception {
        PytestFilter filter = new PytestFilter();
        String raw = fixture("pytest", "typical");
        ExecutionResult exec = new ExecutionResult(1, raw, "", 350L);

        FilterResult result = filter.apply("pytest", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical signals
        assertThat(result.output()).contains("test_mul");
        assertThat(result.output()).contains("test_mod");
        assertThat(result.output()).containsAnyOf("AssertionError", "ZeroDivisionError", "failed");

        // Passing noise eliminated
        assertThat(result.output()).doesNotContain("PASSED");
    }

    @Test
    @DisplayName("Ruff linter composed pipeline summarizes issue counts and violations")
    void testRuffComposedPipeline_diagnostics() throws Exception {
        RuffFilter filter = new RuffFilter();
        String raw = fixture("ruff-check", "typical");
        ExecutionResult exec = new ExecutionResult(1, raw, "", 200L);

        FilterResult result = filter.apply("ruff check", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");
        assertThat(result.output()).contains("issue(s)");
    }

    // =========================================================================
    // JVM & Native Build Ecosystem
    // =========================================================================

    @Test
    @DisplayName("Maven composed pipeline preserves compilation error details and build failure status")
    void testMavenComposedPipeline_failurePreservesCompilationErrors() throws Exception {
        MvnFilter filter = new MvnFilter();
        String raw = fixture("mvn", "failure");
        ExecutionResult exec = new ExecutionResult(1, raw, "", 400L);

        FilterResult result = filter.apply("mvn compile", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical error signals
        assertThat(result.output()).contains("ERROR");
        assertThat(result.output()).containsAnyOf("cannot find symbol", "COMPILATION ERROR");
    }

    @Test
    @DisplayName("Gradle composed pipeline preserves task failure diagnostics and error causes")
    void testGradleComposedPipeline_failurePreservesCompilationFailure() throws Exception {
        GradleFilter filter = new GradleFilter();
        String raw = fixture("gradle", "failure");
        ExecutionResult exec = new ExecutionResult(1, raw, "", 500L);

        FilterResult result = filter.apply("gradle build", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical failure signals
        assertThat(result.output()).contains("BUILD FAILED");
        assertThat(result.output()).contains("compileTestJava FAILED");
        assertThat(result.output()).contains("FAILURE: Build failed with an exception.");
    }

    @Test
    @DisplayName("Make composed pipeline preserves compiler error messages, line numbers, and failing target")
    void testMakeComposedPipeline_failurePreservesErrorsAndTarget() throws Exception {
        MakeFilter filter = new MakeFilter();
        String raw = fixture("make", "failure");
        ExecutionResult exec = new ExecutionResult(2, raw, "", 350L);

        FilterResult result = filter.apply("make", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical compiler error signals
        assertThat(result.output()).containsAnyOf("error:", "Error");
        assertThat(result.output()).contains("src/network.c");
    }

    // =========================================================================
    // GitHub & GitLab CLI Ecosystem
    // =========================================================================

    @Test
    @DisplayName("GitHub CLI pr checks composed pipeline prioritizes failing checks and summarizes passes")
    void testGhPrChecksComposedPipeline_prioritizesFailures() {
        CatalogBackedFilter filter = new CatalogBackedFilter("gh");
        String raw = """
            JVM Tests (Java 21)\tpass\t3m29s\thttps://github.com/org/repo/actions/runs/1/job/101\t
            Native Image (macos-15)\tpass\t4m27s\thttps://github.com/org/repo/actions/runs/1/job/102\t
            Native Image (ubuntu-latest)\tfail\t3m19s\thttps://github.com/org/repo/actions/runs/1/job/103\t
            Native Image (windows-latest)\tpass\t7m51s\thttps://github.com/org/repo/actions/runs/1/job/104\t
            Lint and Format\tpass\t1m12s\thttps://github.com/org/repo/actions/runs/1/job/105\t
            Security Audit\tpass\t45s\thttps://github.com/org/repo/actions/runs/1/job/106\t
            License Check\tpass\t30s\thttps://github.com/org/repo/actions/runs/1/job/107\t
            Integration Tests\tpass\t5m10s\thttps://github.com/org/repo/actions/runs/1/job/108\t
            Documentation Drift\tpass\t20s\thttps://github.com/org/repo/actions/runs/1/job/109\t
            Coverage Upload\tpass\t15s\thttps://github.com/org/repo/actions/runs/1/job/110\t
            """;
        ExecutionResult exec = new ExecutionResult(0, raw, "", 400L);

        FilterResult result = filter.apply("gh pr checks", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical failure signals
        assertThat(result.output()).contains("1 of 10 checks failed");
        assertThat(result.output()).contains("Native Image (ubuntu-latest)");
        assertThat(result.output()).contains("9 checks passed");
    }

    @Test
    @DisplayName("GitHub CLI composed pipeline strictly fails open on child command failure")
    void testGhComposedPipeline_failOpenOnNonZeroExit() {
        CatalogBackedFilter filter = new CatalogBackedFilter("gh");
        String errorMsg = "gh: could not resolve to a Repository\nGraphQL error: not found";
        ExecutionResult exec = new ExecutionResult(1, "", errorMsg, 150L);

        FilterResult result = filter.apply("gh pr checks", exec, config, 0, false);

        assertThat(result.output()).contains("GraphQL error: not found");
    }

    @Test
    @DisplayName("GitHub CLI pr list composed pipeline compresses large PR table with balanced head and tail")
    void testGhPrListComposedPipeline_compressesTable() throws Exception {
        CatalogBackedFilter filter = new CatalogBackedFilter("gh");
        String raw = fixture("gh", "typical");
        ExecutionResult exec = new ExecutionResult(0, raw, "", 500L);

        FilterResult result = filter.apply("gh pr list", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");
        assertThat(result.output()).contains("#001");
        assertThat(result.output()).contains("#040");
    }

    @Test
    @DisplayName("GitHub CLI pr view composed pipeline preserves metadata and compresses large description")
    void testGhPrViewComposedPipeline_keepsMetadataAndCompressesBody() {
        CatalogBackedFilter filter = new CatalogBackedFilter("gh");
        String raw = """
            title:\tBump compiler plugin from 3.15.0 to 3.16.0
            state:\tOPEN
            author:\tdependabot
            labels:\tdependencies, java
            assignees:\t
            reviewers:\t
            projects:\t
            milestone:\t
            number:\t36
            url:\thttps://github.com/org/repo/pull/36
            --
            Bumps compiler from 3.15.0 to 3.16.0.
            <details>
            <summary>Release notes</summary>
            <ul>
            <li>Release note 1</li>
            <li>Release note 2</li>
            <li>Release note 3</li>
            <li>Release note 4</li>
            <li>Release note 5</li>
            <li>Release note 6</li>
            <li>Release note 7</li>
            <li>Release note 8</li>
            <li>Release note 9</li>
            <li>Release note 10</li>
            <li>Release note 11</li>
            <li>Release note 12</li>
            <li>Release note 13</li>
            <li>Release note 14</li>
            </ul>
            </details>
            """;
        ExecutionResult exec = new ExecutionResult(0, raw, "", 180L);

        FilterResult result = filter.apply("gh pr view 36", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical metadata
        assertThat(result.output()).contains("Bump compiler plugin");
        assertThat(result.output()).contains("OPEN");
        assertThat(result.output()).contains("dependabot");
        assertThat(result.output()).doesNotContain("<details>");
        assertThat(result.output()).doesNotContain("assignees:\t");
    }

    @Test
    @DisplayName("GitLab CLI mr view composed pipeline preserves metadata and condenses description")
    void testGlabMrViewComposedPipeline_keepsMetadataAndCompressesBody() {
        CatalogBackedFilter filter = new CatalogBackedFilter("glab");
        String raw = """
            title:\tAdd Postgres SSL mode configuration
            state:\topened
            author:\tjdoe
            labels:\tdatabase, security
            assignees:\t
            milestone:\t
            --
            This merge request adds configurable SSL support.
            <details>
            <summary>Migration details</summary>
            <ul>
            <li>Step 1</li>
            <li>Step 2</li>
            <li>Step 3</li>
            <li>Step 4</li>
            <li>Step 5</li>
            <li>Step 6</li>
            <li>Step 7</li>
            <li>Step 8</li>
            <li>Step 9</li>
            <li>Step 10</li>
            <li>Step 11</li>
            <li>Step 12</li>
            <li>Step 13</li>
            <li>Step 14</li>
            </ul>
            </details>
            """;
        ExecutionResult exec = new ExecutionResult(0, raw, "", 180L);

        FilterResult result = filter.apply("glab mr view 42", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");

        // Critical metadata
        assertThat(result.output()).contains("Postgres SSL mode configuration");
        assertThat(result.output()).contains("opened");
        assertThat(result.output()).contains("jdoe");
        assertThat(result.output()).doesNotContain("<details>");
    }

    // =========================================================================
    // Containers & Cloud Ecosystem
    // =========================================================================

    @Test
    @DisplayName("Docker build composed pipeline compresses intermediate layer transfer noise")
    void testDockerBuildComposedPipeline_compressesBuildNoise() throws Exception {
        DockerBuildFilter filter = new DockerBuildFilter();
        String raw = fixture("docker-build", "typical");
        ExecutionResult exec = new ExecutionResult(0, raw, "", 400L);

        FilterResult result = filter.apply("docker build .", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");
        assertThat(result.output()).containsAnyOf("Successfully", "myapp:latest");
    }

    @Test
    @DisplayName("Kubectl composed pipeline surfaces unhealthy pods requiring attention")
    void testKubectlComposedPipeline_surfacesUnhealthyPods() throws Exception {
        KubectlFilter filter = new KubectlFilter();
        String raw = fixture("kubectl", "pods-unhealthy");
        ExecutionResult exec = new ExecutionResult(0, raw, "", 250L);

        FilterResult result = filter.apply("kubectl get pods", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertThat(result.output()).doesNotContain("condense: error");

        // Unhealthy pods retained under header
        assertThat(result.output()).contains("UNHEALTHY PODS:");
        assertThat(result.output()).containsAnyOf("CrashLoopBackOff", "Pending", "OOMKilled");
    }

    @Test
    @DisplayName("Kubectl logs composed pipeline compresses verbose pod log stream to tail window")
    void testKubectlLogsComposedPipeline_compressesTail() {
        KubectlFilter filter = new KubectlFilter();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            sb.append("2026-09-12T10:00:").append(String.format("%02d", i % 60))
              .append(" INFO Server listening on port 8080 request-id=").append(i).append("\n");
        }
        String raw = sb.toString();
        ExecutionResult exec = new ExecutionResult(0, raw, "", 500L);

        FilterResult result = filter.apply("kubectl logs pod/api-server", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");
        assertThat(result.output()).contains("request-id=60");
    }

    // =========================================================================
    // Git Ecosystem
    // =========================================================================

    @Test
    @DisplayName("Git status composed pipeline condenses branch status and changed files")
    void testGitStatusComposedPipeline_compressesStatus() throws Exception {
        GitStatusFilter filter = new GitStatusFilter();
        String raw = fixture("git-status", "mixed");
        ExecutionResult exec = new ExecutionResult(0, raw, "", 200L);

        FilterResult result = filter.apply("git status", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");
        assertThat(result.output()).containsAnyOf("modified", "Untracked", "Changes");
    }

    @Test
    @DisplayName("Git diff composed pipeline preserves hunks and file diffs with positive compression")
    void testGitDiffComposedPipeline_compressesDiff() throws Exception {
        GitDiffFilter filter = new GitDiffFilter();
        String raw = fixture("git-diff", "typical");
        ExecutionResult exec = new ExecutionResult(0, raw, "", 150L);

        FilterResult result = filter.apply("git diff", exec, config, 0, false);

        assertThat(result.wasFiltered()).isTrue();
        assertCompressed(result);
        assertThat(result.output()).doesNotContain("condense: error");
    }
}
