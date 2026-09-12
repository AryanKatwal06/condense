package com.condense.filter.stage;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlabSummaryStageTest {

    private final GlabSummaryStage stage = GlabSummaryStage.INSTANCE;

    private static FilterContext contextOf(String command) {
        return new FilterContext(command, null, CondenseConfig.defaults(), 0, false);
    }

    private static FilterContext contextOf(String command, ExecutionResult result) {
        return new FilterContext(command, result, CondenseConfig.defaults(), 0, false);
    }

    @Test
    void mrViewCompressesLargeBodyWhileKeepingMetadata() {
        String input = """
            title:\tAdd Postgres SSL mode configuration
            state:\topened
            author:\tjdoe
            labels:\tdatabase, security
            assignees:\t
            milestone:\t
            --
            This merge request adds configurable SSL support to database connections.
            <details>
            <summary>Migration details</summary>
            <ul>
            <li>Step 1 of migration</li>
            <li>Step 2 of migration</li>
            <li>Step 3 of migration</li>
            <li>Step 4 of migration</li>
            <li>Step 5 of migration</li>
            <li>Step 6 of migration</li>
            <li>Step 7 of migration</li>
            <li>Step 8 of migration</li>
            <li>Step 9 of migration</li>
            <li>Step 10 of migration</li>
            <li>Step 11 of migration</li>
            <li>Step 12 of migration</li>
            <li>Step 13 of migration</li>
            <li>Step 14 of migration</li>
            </ul>
            </details>
            """;

        StageResult result = stage.process(input, contextOf("glab mr view 42"));

        assertThat(result.output())
            .contains("title:\tAdd Postgres SSL mode configuration")
            .contains("state:\topened")
            .contains("author:\tjdoe")
            .contains("labels:\tdatabase, security")
            .doesNotContain("assignees:\t")
            .doesNotContain("milestone:\t")
            .doesNotContain("<details>")
            .doesNotContain("<summary>")
            .contains("... (")
            .contains("lines omitted) ...");
    }

    @Test
    void ciViewHighlightsFailedJobs() {
        String input = """
            Pipeline #98765 (branch: main, commit: 1a2b3c4d, status: failed)
            Jobs:
            ✓ build (passed)
            ✓ test (passed)
            ✗ deploy (failed) - step: run ansible
            """;

        StageResult result = stage.process(input, contextOf("glab ci view"));

        assertThat(result.output())
            .contains("Pipeline #98765")
            .contains("✗ 1 of 3 jobs failed:")
            .contains("✗ deploy (failed) - step: run ansible")
            .contains("✓ 2 jobs passed");
    }

    @Test
    void ciViewAllPassing() {
        String input = """
            Pipeline #98765 (branch: main, commit: 1a2b3c4d, status: success)
            Jobs:
            ✓ build (passed)
            ✓ test (passed)
            ✓ deploy (passed)
            """;

        StageResult result = stage.process(input, contextOf("glab ci view"));

        assertThat(result.output())
            .contains("Pipeline #98765")
            .contains("✓ all jobs passed (3 jobs)");
    }

    @Test
    void mrListMaintainsHeadTailCompatibility() {
        StringBuilder input = new StringBuilder();
        input.append("Showing 20 merge requests on acme/app\n");
        for (int i = 1; i <= 20; i++) {
            input.append(String.format("!%03d  mr description %d\n", i, i));
        }

        StageResult result = stage.process(input.toString(), contextOf("glab mr list"));

        assertThat(result.output())
            .contains("Showing 20 merge requests on acme/app")
            .contains("!001")
            .contains("!005")
            .contains("... (9 lines omitted) ...")
            .contains("!020");
    }

    @Test
    void failOpenOnNonZeroExitCode() {
        String failureError = "glab: 404 Not Found (GET https://gitlab.com/api/v4/projects/foo)\n";
        ExecutionResult failedExec = new ExecutionResult(1, failureError, "", 40L);
        FilterContext context = contextOf("glab mr view 999", failedExec);

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
