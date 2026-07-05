package com.condense.filter.golang;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GoTestFilterTest extends FilterTestSupport {

    private GoTestFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new GoTestFilter(); config = CondenseConfig.defaults(); }

    @Test
    void withFailures_showsFailedTests() throws Exception {
        FilterResult r = filter.apply("go test",
            new ExecutionResult(1, fixture("go-test", "json-typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).contains("FAIL: TestMultiply");
        assertThat(r.output()).contains("FAIL: TestDivide");
        // assertCompressed(r);
    }

    @Test
    void allPassing_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("go test",
            success(fixture("go-test", "json-passing")), config, 0, false);
        assertThat(r.output()).containsAnyOf("passed", "✓");
        // assertCompressed(r);
    }

    @Test
    void fallback_showsFailedTests() throws Exception {
        String plainFallbackOutput = "--- FAIL: TestFallback\nFAIL";
        FilterResult r = filter.apply("go test",
            new ExecutionResult(1, plainFallbackOutput, "", 500L),
            config, 0, false);
        assertThat(r.output()).contains("TestFallback");
    }

    @Test
    void failure_passesThrough() {
        FilterResult r = filter.apply("go test",
            failure(1, "go test: error", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("go test",
            new ExecutionResult(1, fixture("go-test", "json-typical"), "", 500L),
            config, 0, false);
        assertThat(r.savingsPct()).isPositive();
    }

    @Test
    void plaintext_failures_areCompressed() {
        String plaintextOutput = """
            running 3 tests
            test tests::test_add ... ok
            --- FAIL: tests::test_multiply (0.00s)
            --- FAIL: tests::test_divide (0.00s)
            FAILED
            """;
        FilterResult r = filter.apply("go test",
            new ExecutionResult(101, plaintextOutput, "", 200L),
            config, 0, false);
        assertThat(r.output()).contains("FAIL");
        assertThat(r.wasFiltered()).isTrue();
    }
}
