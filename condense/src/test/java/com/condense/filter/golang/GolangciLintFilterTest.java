package com.condense.filter.golang;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GolangciLintFilterTest extends FilterTestSupport {

    private GolangciLintFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new GolangciLintFilter(); config = CondenseConfig.defaults(); }

    @Test
    void withFailures_showsErrors() throws Exception {
        FilterResult r = filter.apply("golangci-lint run",
            new ExecutionResult(1, fixture("golangci-lint", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).contains("errcheck");
        // assertCompressed(r);
    }

    @Test
    void allPassing_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("golangci-lint run",
            success(fixture("golangci-lint", "passing")), config, 0, false);
        assertThat(r.output()).containsAnyOf("passed", "✓");
        // assertCompressed(r);
    }

    @Test
    void failure_passesThrough() {
        FilterResult r = filter.apply("golangci-lint run",
            failure(1, "golangci-lint: error", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("golangci-lint run",
            new ExecutionResult(1, fixture("golangci-lint", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.savingsPct()).isPositive();
    }
}
