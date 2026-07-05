package com.condense.filter.node;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NpmInstallFilterTest extends FilterTestSupport {

    private NpmInstallFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new NpmInstallFilter(); config = CondenseConfig.defaults(); }

    @Test
    void typical_showsAddedPackages() throws Exception {
        FilterResult r = filter.apply("npm install",
            success(fixture("npm-install", "typical")), config, 0, false);
        assertThat(r.output()).contains("packages");
        assertCompressed(r);
    }

    @Test
    void withVulns_showsVulns() throws Exception {
        FilterResult r = filter.apply("npm install",
            success(fixture("npm-install", "with-vulns")), config, 0, false);
        assertThat(r.output()).contains("vulnerabilit");
        assertCompressed(r);
    }

    @Test
    void failure_passesThrough() {
        FilterResult r = filter.apply("npm install",
            failure(1, "npm ERR! code ENOENT", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("npm install",
            success(fixture("npm-install", "typical")), config, 0, false);
        assertThat(r.savingsPct()).isPositive();
    }
}
