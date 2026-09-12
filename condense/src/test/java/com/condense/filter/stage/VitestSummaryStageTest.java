package com.condense.filter.stage;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VitestSummaryStageTest {

    private final VitestSummaryStage stage = VitestSummaryStage.INSTANCE;

    private static FilterContext contextOf(ExecutionResult result) {
        return new FilterContext("vitest", result, CondenseConfig.defaults(), 0, false);
    }

    @Test
    void allPassingCompressesToSummary() {
        String input = """
            RUN  v1.0.0 /workspace/project
            ❯ src/math.test.ts (2 tests)
            ✓ adds numbers correctly 5ms
            ✓ subtracts numbers correctly 3ms
            Tests  5 passed (5)
            Duration  42ms
            """;

        StageResult result = stage.process(input, contextOf(new ExecutionResult(0, input, "", 50L)));
        assertThat(result.output()).isEqualTo("Tests  5 passed (5)");
    }

    @Test
    void allPassingWithNoSummaryReturnsCheckmark() {
        String input = "✓ adds numbers correctly 5ms\n";
        StageResult result = stage.process(input, contextOf(new ExecutionResult(0, input, "", 20L)));
        assertThat(result.output()).isEqualTo("✓ all tests passed");
    }

    @Test
    void failuresPreserveAssertionDiffAndDetails() {
        String input = """
            RUN  v1.0.0 /workspace/project
            ❯ src/math.test.ts (3 tests | 1 failed)
            ✓ adds numbers correctly 5ms
            ✓ subtracts numbers correctly 3ms
            × divides by zero 2ms
            → Expected 0, received Infinity
            ❯ src/utils.test.ts (2 tests | 1 failed)
            ✓ formats date correctly 1ms
            × handles null input 2ms
            → Cannot read property 'length' of null
            Tests  2 failed | 3 passed (5)
            Duration  42ms
            """;

        StageResult result = stage.process(input, contextOf(new ExecutionResult(1, input, "", 100L)));
        assertThat(result.output()).isEqualTo("""
            vitest: 2 failure(s)
              × divides by zero 2ms
                → Expected 0, received Infinity
              × handles null input 2ms
                → Cannot read property 'length' of null
            Tests  2 failed | 3 passed (5)""");
    }

    @Test
    void stackTracesAreCappedAtFiveLines() {
        String input = """
            × calculation error
                AssertionError: expected false to be true
                  at frameOne (src/math.ts:1:1)
                  at frameTwo (src/math.ts:2:1)
                  at frameThree (src/math.ts:3:1)
                  at frameFour (src/math.ts:4:1)
                  at frameFive (src/math.ts:5:1)
                  at frameSix (src/math.ts:6:1)
                  at frameSeven (src/math.ts:7:1)
            Tests  1 failed (1)
            """;

        StageResult result = stage.process(input, contextOf(new ExecutionResult(1, input, "", 100L)));
        assertThat(result.output()).contains("at frameFive");
        assertThat(result.output()).contains("... (stack trace omitted) ...");
        assertThat(result.output()).doesNotContain("at frameSix");
        assertThat(result.output()).doesNotContain("at frameSeven");
    }

    @Test
    void failOpenOnNonZeroExitWithUnknownFormat() {
        String raw = "vitest: failed to start worker thread pool\n";
        StageResult result = stage.process(raw, contextOf(new ExecutionResult(1, raw, "", 50L)));
        assertThat(result.output()).isEqualTo(raw);
    }

    @Test
    void handlesEmptyOrMalformedInputGracefully() {
        assertThat(stage.process("", FilterContext.empty()).output()).isEmpty();
        assertThat(stage.process(null, FilterContext.empty()).output()).isEmpty();
        assertThat(stage.process("   \n\t  ", FilterContext.empty()).output()).isEqualTo("   \n\t  ");
    }
}
