package com.condense.filter.build;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MakeFilterTest extends FilterTestSupport {

    private MakeFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new MakeFilter(); config = CondenseConfig.defaults(); }

    @Test
    void failure_showsError() throws Exception {
        FilterResult r = filter.apply("make",
            failure(2, fixture("make", "failure"), ""), config, 0, false);
        assertThat(r.output()).contains("error");
        assertCompressed(r);
    }

    @Test
    void success_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("make",
            success(fixture("make", "success")), config, 0, false);
        assertThat(r.output()).containsAnyOf("success", "✓");
        assertCompressed(r);
    }

    @Test
    void empty_passesThrough() {
        FilterResult r = filter.apply("make",
            failure(1, "make: error", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("make",
            failure(2, fixture("make", "failure"), ""), config, 0, false);
        assertThat(r.savingsPct()).isPositive();
    }
}
