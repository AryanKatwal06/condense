package com.condense.filter.python;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RuffFilterTest extends FilterTestSupport {

    private RuffFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new RuffFilter(); config = CondenseConfig.defaults(); }

    @Test
    void withFailures_showsErrors() throws Exception {
        FilterResult r = filter.apply("ruff check",
            new ExecutionResult(1, fixture("ruff-check", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).contains("issue(s)");
        // assertCompressed(r);
    }

    @Test
    void allPassing_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("ruff check",
            success(fixture("ruff-check", "passing")), config, 0, false);
        assertThat(r.output()).containsAnyOf("passed", "✓");
        // assertCompressed(r);
    }

    @Test
    void failure_passesThrough() {
        FilterResult r = filter.apply("ruff check",
            failure(1, "ruff: command not found", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("ruff check",
            new ExecutionResult(1, fixture("ruff-check", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.savingsPct()).isPositive();
    }
}
