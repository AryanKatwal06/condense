package com.condense.filter.git;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitDiffFilterTest extends FilterTestSupport {

    private GitDiffFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new GitDiffFilter(); config = CondenseConfig.defaults(); }

    @Test
    void statSummary_extractsSummaryLine() throws Exception {
        FilterResult r = filter.apply("git diff",
            success(fixture("git-diff", "stat")), config, 0, false);
        assertThat(r.output()).contains("files changed");
        assertCompressed(r);
    }

    @Test
    void patchDiff_countsAddedAndRemovedLines() throws Exception {
        FilterResult r = filter.apply("git diff",
            success(fixture("git-diff", "typical")), config, 0, false);
        assertThat(r.output()).containsAnyOf("+", "files changed");
        assertCompressed(r);
    }

    @Test
    void nonZeroExit_passesThrough() {
        FilterResult r = filter.apply("git diff",
            failure(1, "fatal: bad revision"), config, 0, false);
        assertPassthrough(r);
        assertThat(r.output()).contains("fatal:");
    }
}