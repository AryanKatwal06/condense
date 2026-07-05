package com.condense.filter.node;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TscFilterTest extends FilterTestSupport {

    private TscFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new TscFilter(); config = CondenseConfig.defaults(); }

    @Test
    void withFailures_showsErrorLines() throws Exception {
        FilterResult r = filter.apply("tsc",
            new ExecutionResult(2, fixture("tsc", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).contains("src/api/client.ts");
        // assertCompressed(r);
    }

    @Test
    void allPassing_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("tsc",
            success(fixture("tsc", "passing")), config, 0, false);
        assertThat(r.output()).containsAnyOf("passed", "✓", "no errors"); // Check real logic in TscFilter
        // assertCompressed(r);
    }

    @Test
    void failure_passesThrough() {
        FilterResult r = filter.apply("tsc",
            failure(1, "tsc: command not found", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("tsc",
            new ExecutionResult(2, fixture("tsc", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.savingsPct()).isPositive();
    }
}
