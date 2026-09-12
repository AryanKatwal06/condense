package com.condense.filter.stage;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JestSummaryStageTest {

    private final JestSummaryStage stage = JestSummaryStage.INSTANCE;

    private static FilterContext contextOf(ExecutionResult result) {
        return new FilterContext("jest", result, CondenseConfig.defaults(), 0, false);
    }

    @Test
    void allPassingCompressesToSummary() {
        String input = """
            PASS src/utils/math.test.js
            PASS src/components/Button.test.js
            Test Suites: 2 passed, 2 total
            Tests:       8 passed, 8 total
            Snapshots:   0 total
            Time:        1.234 s
            """;

        StageResult result = stage.process(input, contextOf(new ExecutionResult(0, input, "", 100L)));
        assertThat(result.output()).isEqualTo("""
            Test Suites: 2 passed, 2 total
            Tests:       8 passed, 8 total""");
    }

    @Test
    void allPassingWithNoSummaryLinesReturnsCheckmark() {
        String input = "PASS src/utils/math.test.js\n";
        StageResult result = stage.process(input, contextOf(new ExecutionResult(0, input, "", 50L)));
        assertThat(result.output()).isEqualTo("✓ all tests passed");
    }

    @Test
    void failuresPreserveErrorMessagesAndDiffs() {
        String input = """
            PASS src/utils/math.test.js
            FAIL src/components/Button.test.js
            ● Button › renders without crashing
            expect(received).toBeTruthy()
            Received: false
            FAIL src/api/auth.test.js
            ● auth › should reject invalid token
            Error: timeout of 5000ms exceeded
            Test Suites: 2 failed, 1 passed, 3 total
            Tests:       2 failed, 8 passed, 10 total
            Snapshots:   0 total
            Time:        3.456 s
            """;

        StageResult result = stage.process(input, contextOf(new ExecutionResult(1, input, "", 200L)));
        assertThat(result.output()).isEqualTo("""
            jest: 2 suite(s) failed
              FAIL: src/components/Button.test.js
              ● Button › renders without crashing
                expect(received).toBeTruthy()
                Received: false
              FAIL: src/api/auth.test.js
              ● auth › should reject invalid token
                Error: timeout of 5000ms exceeded
            Test Suites: 2 failed, 1 passed, 3 total
            Tests:       2 failed, 8 passed, 10 total""");
    }

    @Test
    void stackTracesAreCappedAtFiveLines() {
        String input = """
            FAIL src/example.test.js
            ● test failure
                Error: assertion failed
                  at frameOne (src/example.js:10:5)
                  at frameTwo (src/example.js:20:5)
                  at frameThree (src/example.js:30:5)
                  at frameFour (src/example.js:40:5)
                  at frameFive (src/example.js:50:5)
                  at frameSix (src/example.js:60:5)
                  at frameSeven (src/example.js:70:5)
            Test Suites: 1 failed, 1 total
            Tests:       1 failed, 1 total
            """;

        StageResult result = stage.process(input, contextOf(new ExecutionResult(1, input, "", 200L)));
        assertThat(result.output()).contains("at frameFive");
        assertThat(result.output()).contains("... (stack trace omitted) ...");
        assertThat(result.output()).doesNotContain("at frameSix");
        assertThat(result.output()).doesNotContain("at frameSeven");
    }

    @Test
    void failOpenOnNonZeroExitWithUnknownFormat() {
        String raw = "error: unable to find jest configuration file in root directory\n";
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
