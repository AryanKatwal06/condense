package com.condense.filter.node;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VitestFilterTest extends FilterTestSupport {

    private VitestFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new VitestFilter(); config = CondenseConfig.defaults(); }

    @Test
    void withFailures_showsFailedTests() throws Exception {
        FilterResult r = filter.apply("vitest",
            new ExecutionResult(1, fixture("vitest", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).contains("divides by zero");
        assertThat(r.output()).contains("handles null input");
        assertCompressed(r);
    }

    @Test
    void allPassing_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("vitest",
            success(fixture("vitest", "passing")), config, 0, false);
        assertThat(r.output()).containsAnyOf("passed", "✓");
        assertCompressed(r);
    }

    @Test
    void failure_passesThrough() {
        FilterResult r = filter.apply("vitest",
            failure(1, "vitest: command not found", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("vitest",
            new ExecutionResult(1, fixture("vitest", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.savingsPct()).isPositive();
    }
}
