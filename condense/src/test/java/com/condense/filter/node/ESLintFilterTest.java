package com.condense.filter.node;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ESLintFilterTest extends FilterTestSupport {

    private ESLintFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new ESLintFilter(); config = CondenseConfig.defaults(); }

    @Test
    void withFailures_showsErrorSummary() throws Exception {
        FilterResult r = filter.apply("eslint",
            new ExecutionResult(1, fixture("eslint", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).contains("error(s)");
        assertCompressed(r);
    }

    @Test
    void allPassing_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("eslint",
            success(fixture("eslint", "passing")), config, 0, false);
        assertThat(r.output()).containsAnyOf("passed", "✓");
        assertCompressed(r);
    }

    @Test
    void failure_passesThrough() {
        FilterResult r = filter.apply("eslint",
            failure(1, "eslint: command not found", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("eslint",
            new ExecutionResult(1, fixture("eslint", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.savingsPct()).isPositive();
    }
}
