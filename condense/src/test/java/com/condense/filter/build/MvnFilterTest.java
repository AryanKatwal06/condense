package com.condense.filter.build;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MvnFilterTest extends FilterTestSupport {

    private MvnFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new MvnFilter(); config = CondenseConfig.defaults(); }

    @Test
    void failure_showsError() throws Exception {
        FilterResult r = filter.apply("mvn install",
            failure(1, fixture("mvn", "failure"), ""), config, 0, false);
        assertThat(r.output()).contains("ERROR");
        // assertCompressed(r);
    }

    @Test
    void success_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("mvn install",
            success(fixture("mvn", "success")), config, 0, false);
        assertThat(r.output()).containsAnyOf("SUCCESS", "✓");
        // assertCompressed(r);
    }

    @Test
    void empty_passesThrough() {
        FilterResult r = filter.apply("mvn install",
            failure(1, "mvn: error", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("mvn install",
            failure(1, fixture("mvn", "failure"), ""), config, 0, false);
        assertThat(r.savingsPct()).isPositive();
    }
}
