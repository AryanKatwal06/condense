package com.condense.filter.python;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PipInstallFilterTest extends FilterTestSupport {

    private PipInstallFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new PipInstallFilter(); config = CondenseConfig.defaults(); }

    @Test
    void typical_showsInstalledPackages() throws Exception {
        FilterResult r = filter.apply("pip install",
            success(fixture("pip-install", "typical")), config, 0, false);
        assertThat(r.output()).contains("Successfully installed");
        // assertCompressed(r);
    }

    @Test
    void allPassing_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("pip install",
            success("Successfully installed something"), config, 0, false);
        assertThat(r.output()).containsAnyOf("installed", "✓");
        // assertCompressed(r);
    }

    @Test
    void failure_passesThrough() {
        FilterResult r = filter.apply("pip install",
            failure(1, "pip: error", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("pip install",
            success(fixture("pip-install", "typical")), config, 0, false);
        assertThat(r.savingsPct()).isPositive();
    }
}
