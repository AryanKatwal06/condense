package com.condense.filter.stage;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JestSummaryStageAdversarialTest {

    @Test
    @DisplayName("Jest parser survives ANSI codes, interleaved console.log with PASS, and multi-failure suite")
    void adversarialJestRun_preservesAllFailures_survivesAnsiAndConsoleLog() {
        StringBuilder raw = new StringBuilder();

        // 80 passing test suites
        for (int i = 1; i <= 80; i++) {
            raw.append("PASS src/components/suite_").append(i).append(".test.tsx (0.").append(i % 10).append("s)\n");
        }

        // Realistic ANSI-escaped FAIL header: \u001B[1m\u001B[31mFAIL\u001B[39m\u001B[22m
        raw.append("\u001B[1m\u001B[31mFAIL\u001B[39m\u001B[22m tests/billing/subscription.test.ts\n");

        // Interleaved console.log with PASS marker that previously aborted the suite
        raw.append("  console.log\n");
        raw.append("    PASS: database transaction isolation verified\n");
        raw.append("\n");

        // Failure 1: Large assertion diff (> 20 lines)
        raw.append("  ● Billing Service > should compute tiered pricing [special chars: (plan|tier) $99.99]\n");
        raw.append("\n");
        raw.append("    expect(received).toEqual(expected) // deep equality\n");
        raw.append("\n");
        raw.append("    - Expected  - 1\n");
        raw.append("    + Received  + 1\n");
        raw.append("\n");
        raw.append("      Object {\n");
        raw.append("        \"baseRate\": 100,\n");
        for (int k = 1; k <= 18; k++) {
            raw.append("        \"tier_").append(k).append("\": ").append(k * 10).append(",\n");
        }
        raw.append("    -   \"discount\": 15,\n");
        raw.append("    +   \"discount\": 0,\n");
        raw.append("      }\n");
        raw.append("\n");
        raw.append("      45 |   it('should compute tiered pricing', () => {\n");
        raw.append("    > 46 |     expect(calc(sub)).toEqual(expected);\n");
        raw.append("         |                       ^\n");
        raw.append("      at Object.toEqual (tests/billing/subscription.test.ts:46:23)\n");
        raw.append("      at Promise.then.completed (node_modules/jest-circus/build/utils.js:391:28)\n");

        // Failure 2: Thrown TypeError
        raw.append("  ● Billing Service > should throw on negative billing period\n");
        raw.append("\n");
        raw.append("    TypeError: Invalid billing cycle: duration cannot be negative\n");
        raw.append("\n");
        raw.append("      58 | export function validateCycle(days: number) {\n");
        raw.append("    > 59 |   if (days < 0) throw new TypeError('Invalid billing cycle: duration cannot be negative');\n");
        raw.append("         |                       ^\n");
        raw.append("      at validateCycle (src/billing/cycle.ts:59:23)\n");
        raw.append("      at Object.test (tests/billing/subscription.test.ts:70:5)\n");

        // Failure 3: Async Timeout
        raw.append("  ● Billing Service > should webhook stripe event before timeout\n");
        raw.append("\n");
        raw.append("    Timeout - Async callback was not invoked within the 5000 ms timeout specified by jest.setTimeout.\n");
        raw.append("\n");
        raw.append("      at Timeout._onTimeout (node_modules/jest-jasmine2/build/queueRunner.js:43:12)\n");

        // Summary footer
        raw.append("Test Suites: 1 failed, 80 passed, 81 total\n");
        raw.append("Tests:       3 failed, 80 passed, 83 total\n");
        raw.append("Snapshots:   0 total\n");
        raw.append("Time:        5.432 s\n");

        ExecutionResult execResult = new ExecutionResult(1, raw.toString(), "", 5432L);
        FilterContext context = FilterContext.of("jest", execResult, CondenseConfig.defaults(), 0, false);

        StageResult stageResult = JestSummaryStage.INSTANCE.process(raw.toString(), context);
        String output = stageResult.output();

        // Verification 1: All 3 failing test names must appear in the output
        assertThat(output)
            .as("Failure 1 name must be preserved despite long diff")
            .contains("Billing Service > should compute tiered pricing");

        assertThat(output)
            .as("Failure 2 name must be preserved and not suppressed by failure 1")
            .contains("Billing Service > should throw on negative billing period");

        assertThat(output)
            .as("Failure 3 name must be preserved even as 3rd failure in suite")
            .contains("Billing Service > should webhook stripe event before timeout");

        // Verification 2: The console.log containing 'PASS' did not truncate the suite
        assertThat(output)
            .as("Suite header must be present")
            .contains("FAIL: tests/billing/subscription.test.ts");

        // Verification 3: Detail diff and errors are retained
        assertThat(output).contains("TypeError: Invalid billing cycle");
        assertThat(output).contains("Timeout - Async callback was not invoked");

        // Verification 4: The 80 passing test suites are suppressed from the summary
        assertThat(output)
            .as("Passing test suites must be suppressed")
            .doesNotContain("PASS src/components/suite_1.test.tsx");

        // Verification 5: Structured IR TestDocument was published
        assertThat(context.documentBuilder().isPopulated()).isTrue();
        Document doc = context.documentBuilder().build("jest", "jest", 1, true, null);
        assertThat(doc).isNotNull();
        assertThat(doc.kind()).isEqualTo(Document.DocumentKind.TEST);
        Document.TestDocument testDoc = (Document.TestDocument) doc.document();
        assertThat(testDoc.cases()).hasSize(3);
        assertThat(testDoc.failed()).isEqualTo(3);
        assertThat(testDoc.passed()).isEqualTo(80);
        assertThat(testDoc.cases().get(0).name()).contains("Billing Service > should compute tiered pricing");
        assertThat(testDoc.cases().get(1).name()).contains("Billing Service > should throw on negative billing period");
        assertThat(testDoc.cases().get(2).name()).contains("Billing Service > should webhook stripe event before timeout");
    }
}
