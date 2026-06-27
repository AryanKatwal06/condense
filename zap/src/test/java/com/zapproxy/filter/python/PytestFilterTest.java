package com.zapproxy.filter.python;

import com.zapproxy.core.*;
import com.zapproxy.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PytestFilterTest extends FilterTestSupport {

    private PytestFilter filter;
    private ZapConfig config;

    @BeforeEach
    void setUp() { filter = new PytestFilter(); config = ZapConfig.defaults(); }

    @Test
    void withFailures_showsFailedTestNames() throws Exception {
        FilterResult r = filter.apply("pytest",
            new ExecutionResult(1, fixture("pytest", "typical"), "", 200L),
            config, 0, false);
        assertThat(r.output()).contains("test_mul");
        assertThat(r.output()).contains("test_mod");
        assertCompressed(r);
    }

    @Test
    void withFailures_showsFinalSummaryLine() throws Exception {
        FilterResult r = filter.apply("pytest",
            new ExecutionResult(1, fixture("pytest", "typical"), "", 200L),
            config, 0, false);
        assertThat(r.output()).contains("passed");
        assertThat(r.output()).contains("failed");
    }

    @Test
    void doesNotShowPassedTestLines() throws Exception {
        FilterResult r = filter.apply("pytest",
            new ExecutionResult(1, fixture("pytest", "typical"), "", 200L),
            config, 0, false);
        assertThat(r.output()).doesNotContain("PASSED");
    }
}