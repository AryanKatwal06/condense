package com.condense.filter.stage;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaywrightSummaryStageTest {

    private final PlaywrightSummaryStage stage = PlaywrightSummaryStage.INSTANCE;

    private static FilterContext contextOf(ExecutionResult result) {
        return new FilterContext("playwright test", result, CondenseConfig.defaults(), 0, false);
    }

    @Test
    void allPassingCompresses() {
        String input = """
              ✓ 1 auth.spec.ts:10:3 › login (120ms)
              ✓ 2 auth.spec.ts:25:3 › logout (80ms)
              2 passed (1.2s)
            """;

        StageResult result = stage.process(input, contextOf(new ExecutionResult(0, input, "", 100L)));
        assertThat(result.output()).isEqualTo("  2 passed (1.2s)");
    }

    @Test
    void allPassingWithNoSummaryReturnsCheckmark() {
        String input = "  ✓ 1 auth.spec.ts:10:3 › login (120ms)\n";
        StageResult result = stage.process(input, contextOf(new ExecutionResult(0, input, "", 50L)));
        assertThat(result.output()).isEqualTo("✓ all tests passed");
    }

    @Test
    void failuresPreserveTestTitleAndDiff() {
        String input = """
              ✓ noise spec 1 (12ms)
              1) invoice.spec.ts:12:5 › invoice total ──────────────────────
                Error: expect(received).toBe(expected)
                Expected: 100
                Received: 50
                  11 | expect(total).toBe(100);
                     |               ^
                    at tests/invoice.spec.ts:11:17
              1 failed (1.5s)
            """;

        StageResult result = stage.process(input, contextOf(new ExecutionResult(1, input, "", 200L)));
        assertThat(result.output()).contains("1) invoice.spec.ts:12:5 › invoice total");
        assertThat(result.output()).contains("Error: expect(received).toBe(expected)");
        assertThat(result.output()).contains("Expected: 100");
        assertThat(result.output()).contains("Received: 50");
        assertThat(result.output()).contains("1 failed (1.5s)");
        assertThat(result.output()).doesNotContain("noise spec 1");
    }

    @Test
    void callLogsAndAttachmentsAreFilteredOut() {
        String input = """
              1) invoice.spec.ts:12:5 › invoice total ──────────────────────
                Error: element not found
                attachment #1: screenshot (image/png) ─────────────────────
                test-results/invoice-total/test-failed-1.png
                ───────────────────────────────────────────────────────────
                Call log:
                  - waiting for locator('button#submit')
                  - locator.click
              1 failed
            """;

        StageResult result = stage.process(input, contextOf(new ExecutionResult(1, input, "", 150L)));
        assertThat(result.output()).contains("1) invoice.spec.ts:12:5 › invoice total");
        assertThat(result.output()).contains("Error: element not found");
        assertThat(result.output()).doesNotContain("Call log:");
        assertThat(result.output()).doesNotContain("waiting for locator");
        assertThat(result.output()).doesNotContain("attachment #1");
        assertThat(result.output()).doesNotContain("test-failed-1.png");
        assertThat(result.output()).contains("1 failed");
    }

    @Test
    void stackTracesAreCappedAtFiveLines() {
        String input = """
              1) example.spec.ts:10:1 › failure
                Error: failed
                  at frameOne (tests/one.ts:1:1)
                  at frameTwo (tests/two.ts:2:1)
                  at frameThree (tests/three.ts:3:1)
                  at frameFour (tests/four.ts:4:1)
                  at frameFive (tests/five.ts:5:1)
                  at frameSix (tests/six.ts:6:1)
                  at frameSeven (tests/seven.ts:7:1)
              1 failed
            """;

        StageResult result = stage.process(input, contextOf(new ExecutionResult(1, input, "", 100L)));
        assertThat(result.output()).contains("at frameFive");
        assertThat(result.output()).contains("... (stack trace omitted) ...");
        assertThat(result.output()).doesNotContain("at frameSix");
        assertThat(result.output()).doesNotContain("at frameSeven");
    }

    @Test
    void failOpenOnUnknownFormat() {
        String raw = "playwright: browser executable not found\n";
        StageResult result = stage.process(raw, contextOf(new ExecutionResult(1, raw, "", 100L)));
        assertThat(result.output()).isEqualTo(raw);
    }

    @Test
    void handlesEmptyOrMalformedInputGracefully() {
        assertThat(stage.process("", FilterContext.empty()).output()).isEmpty();
        assertThat(stage.process(null, FilterContext.empty()).output()).isEmpty();
        assertThat(stage.process("   \n\t  ", FilterContext.empty()).output()).isEqualTo("   \n\t  ");
    }
}
