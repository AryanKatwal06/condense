package com.condense.filter.node;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JestFilterTest extends FilterTestSupport {

    private JestFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new JestFilter(); config = CondenseConfig.defaults(); }

    @Test
    void withFailures_showsFailedSuiteNames() throws Exception {
        FilterResult r = filter.apply("jest",
            new ExecutionResult(1, fixture("jest", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).contains("FAIL: src/components/Button.test.js");
        assertThat(r.output()).contains("FAIL: src/api/auth.test.js");
        assertCompressed(r);
    }

    @Test
    void withFailures_doesNotShowPassingSuites() throws Exception {
        FilterResult r = filter.apply("jest",
            new ExecutionResult(1, fixture("jest", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).doesNotContain("math.test.js");
    }

    @Test
    void allPassing_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("jest",
            success(fixture("jest", "passing")), config, 0, false);
        assertThat(r.output()).containsAnyOf("passed", "✓", "Ran all test suites.");
        assertCompressed(r);
    }

    @Test
    void withFailures_includesSummaryLine() throws Exception {
        FilterResult r = filter.apply("jest",
            new ExecutionResult(1, fixture("jest", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).containsAnyOf("Tests:", "Test Suites:");
    }

    @Test
    void failure_passesThrough() {
        FilterResult r = filter.apply("jest",
            failure(1, "jest: command not found", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("jest",
            new ExecutionResult(1, fixture("jest", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.savingsPct()).isPositive();
    }
}
