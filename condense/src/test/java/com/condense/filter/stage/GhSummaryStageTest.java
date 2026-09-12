package com.condense.filter.stage;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GhSummaryStageTest {

    private final GhSummaryStage stage = GhSummaryStage.INSTANCE;

    private static FilterContext contextOf(String command) {
        return new FilterContext(command, null, CondenseConfig.defaults(), 0, false);
    }

    private static FilterContext contextOf(String command, ExecutionResult result) {
        return new FilterContext(command, result, CondenseConfig.defaults(), 0, false);
    }

    @Test
    void prViewCompressesLargeMarkdownBodyWhileKeepingMetadata() {
        String input = """
            title:\tBump org.apache.maven.plugins:maven-compiler-plugin from 3.15.0 to 3.16.0
            state:\tOPEN
            author:\tdependabot
            labels:\tdependencies, java
            assignees:\t
            reviewers:\t
            projects:\t
            milestone:\t
            number:\t36
            url:\thttps://github.com/AryanKatwal06/condense/pull/36
            additions:\t1
            deletions:\t1
            auto-merge:\tdisabled
            --
            Bumps [org.apache.maven.plugins:maven-compiler-plugin](https://github.com/apache/maven-compiler-plugin) from 3.15.0 to 3.16.0.
            <details>
            <summary>Release notes</summary>
            <p><em>Sourced from releases.</em></p>
            <blockquote>
            <h2>3.16.0</h2>
            <h2>🚀 New features and improvements</h2>
            <ul>
            <li>Line 1 of release notes</li>
            <li>Line 2 of release notes</li>
            <li>Line 3 of release notes</li>
            <li>Line 4 of release notes</li>
            <li>Line 5 of release notes</li>
            <li>Line 6 of release notes</li>
            <li>Line 7 of release notes</li>
            <li>Line 8 of release notes</li>
            <li>Line 9 of release notes</li>
            <li>Line 10 of release notes</li>
            <li>Line 11 of release notes</li>
            <li>Line 12 of release notes</li>
            <li>Line 13 of release notes</li>
            <li>Line 14 of release notes</li>
            </ul>
            </blockquote>
            </details>
            """;

        StageResult result = stage.process(input, contextOf("gh pr view 36"));

        assertThat(result.output())
            .contains("title:\tBump org.apache.maven.plugins:maven-compiler-plugin")
            .contains("state:\tOPEN")
            .contains("author:\tdependabot")
            .contains("labels:\tdependencies, java")
            .contains("url:\thttps://github.com/AryanKatwal06/condense/pull/36")
            .doesNotContain("assignees:\t")
            .doesNotContain("projects:\t")
            .doesNotContain("milestone:\t")
            .doesNotContain("<details>")
            .doesNotContain("<summary>")
            .doesNotContain("<ul>")
            .contains("... (")
            .contains("lines omitted) ...");
    }

    @Test
    void prChecksPrioritizesFailures() {
        String input = """
            JVM Tests (Java 21)\tpass\t3m29s\thttps://github.com/org/repo/actions/runs/1/job/101\t
            Native Image (macos-15)\tpass\t4m27s\thttps://github.com/org/repo/actions/runs/1/job/102\t
            Native Image (ubuntu-latest)\tfail\t3m19s\thttps://github.com/org/repo/actions/runs/1/job/103\t
            Native Image (windows-latest)\tpass\t7m51s\thttps://github.com/org/repo/actions/runs/1/job/104\t
            """;

        StageResult result = stage.process(input, contextOf("gh pr checks"));

        assertThat(result.output())
            .contains("✗ 1 of 4 checks failed:")
            .contains("✗ Native Image (ubuntu-latest) (fail, 3m19s) https://github.com/org/repo/actions/runs/1/job/103")
            .contains("✓ 3 checks passed");
    }

    @Test
    void prChecksAllPassing() {
        String input = """
            JVM Tests (Java 21)\tpass\t3m29s\thttps://github.com/org/repo/actions/runs/1/job/101\t
            Native Image (macos-15)\tpass\t4m27s\thttps://github.com/org/repo/actions/runs/1/job/102\t
            Native Image (windows-latest)\tpass\t7m51s\thttps://github.com/org/repo/actions/runs/1/job/104\t
            """;

        StageResult result = stage.process(input, contextOf("gh pr checks"));

        assertThat(result.output()).isEqualTo("✓ all checks passed (3 checks)");
    }

    @Test
    void runViewCompressesAnnotationsAndHighlightsFailedStep() {
        String input = """
            X main Build & Test · 34385868770
            Triggered via push about 2 days ago

            JOBS
            ✓ JVM Tests (Java 21) in 3m35s (ID 102581842804)
            ✓ Native Image (macos-15) in 2m55s (ID 102583070549)
            X Native Image (ubuntu-latest) in 35s (ID 102583070691)
              ✓ Set up job
              ✓ Run actions/checkout@v4
              ✓ Set up GraalVM JDK 21
              ✓ Cache Maven packages
              X Install musl toolchain and zlib (linux-x64 only)
              - Check Java and OS Arch Properties
              - Build native image
            ✓ Native Image (windows-latest) in 8m10s (ID 102583070730)

            ANNOTATIONS
            - macOS Cold Start Run 5: 22ms
            Native Image (macos-15): .github#27

            - Linux Cold Start Run 1: 12ms
            Native Image (ubuntu-24.04-arm): .github#23

            X Process completed with exit code 100.
            Native Image (ubuntu-latest): .github#32

            ARTIFACTS
            condense-windows-x64
            condense-linux-aarch64

            To see what failed, try: gh run view 34385868770 --log-failed
            View this run on GitHub: https://github.com/org/repo/actions/runs/34385868770
            """;

        StageResult result = stage.process(input, contextOf("gh run view 34385868770"));

        assertThat(result.output())
            .contains("X main Build & Test · 34385868770")
            .contains("Triggered via push about 2 days ago")
            .contains("JOBS")
            .contains("✓ JVM Tests (Java 21) in 3m35s")
            .contains("X Native Image (ubuntu-latest) in 35s")
            .contains("X Install musl toolchain and zlib (linux-x64 only)")
            .doesNotContain("✓ Set up job")
            .doesNotContain("✓ Run actions/checkout")
            .contains("ANNOTATIONS")
            .contains("X Process completed with exit code 100.")
            .doesNotContain("Cold Start Run")
            .contains("ARTIFACTS")
            .contains("condense-windows-x64")
            .doesNotContain("To see what failed");
    }

    @Test
    void runViewPassingRunCompactsCleanly() {
        String input = """
            ✓ main Build & Test · 34678483472
            Triggered via push about 30 minutes ago

            JOBS
            ✓ JVM Tests (Java 21) in 4m21s (ID 103512554159)
            ✓ Native Image (macos-15) in 3m2s (ID 103513072138)
            ✓ Native Image (windows-latest) in 8m59s (ID 103513072154)

            ANNOTATIONS
            - macOS Cold Start Run 1: 33ms
            Native Image (macos-15): .github#23

            ARTIFACTS
            condense-windows-x64

            For more information about a job, try: gh run view --job=<job-id>
            """;

        StageResult result = stage.process(input, contextOf("gh run view 34678483472"));

        assertThat(result.output())
            .contains("✓ main Build & Test · 34678483472")
            .contains("JOBS")
            .contains("✓ JVM Tests (Java 21) in 4m21s")
            .doesNotContain("ANNOTATIONS")
            .doesNotContain("Cold Start Run")
            .contains("ARTIFACTS")
            .doesNotContain("For more information");
    }

    @Test
    void prListMaintainsHeadTailCompatibility() {
        StringBuilder input = new StringBuilder();
        input.append("Showing 20 pull requests in acme/app\n");
        for (int i = 1; i <= 20; i++) {
            input.append(String.format("#%03d  pr description %d\n", i, i));
        }

        StageResult result = stage.process(input.toString(), contextOf("gh pr list"));

        assertThat(result.output())
            .contains("Showing 20 pull requests in acme/app")
            .contains("#001")
            .contains("#005")
            .contains("... (9 lines omitted) ...")
            .contains("#020");
    }

    @Test
    void failOpenOnNonZeroExitCode() {
        String failureError = "graphql error: 'Could not resolve to a Repository'\n";
        ExecutionResult failedExec = new ExecutionResult(1, failureError, "", 50L);
        FilterContext context = contextOf("gh pr view 999", failedExec);

        StageResult result = stage.process(failureError, context);

        assertThat(result.output()).isEqualTo(failureError);
    }

    @Test
    void handlesNullOrBlankInputGracefully() {
        assertThat(stage.process("", FilterContext.empty()).output()).isEmpty();
        assertThat(stage.process(null, FilterContext.empty()).output()).isEmpty();
        assertThat(stage.process("   \n  \n", FilterContext.empty()).output()).isEqualTo("   \n  \n");
    }
}
