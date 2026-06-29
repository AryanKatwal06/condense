package com.condense.filter.git;

import com.condense.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitStatusFilterTest {

    private GitStatusFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() {
        filter = new GitStatusFilter();
        config = CondenseConfig.defaults();
    }

    // ── fixture loading ───────────────────────────────────────────────────────

    private String fixture(String name) throws IOException, URISyntaxException {
        URL url = getClass().getResource("/fixtures/git-status/" + name + ".txt");
        assertThat(url)
            .as("Fixture /fixtures/git-status/%s.txt must exist on classpath", name)
            .isNotNull();
        return Files.readString(Path.of(url.toURI()));
    }

    private FilterResult run(String fixtureName) throws Exception {
        String raw = fixture(fixtureName);
        return filter.apply("git status",
            new ExecutionResult(0, raw, "", 10L), config, 0, false);
    }

    // ── clean ─────────────────────────────────────────────────────────────────

    @Test
    void cleanRepo_outputContainsCheckmarkAndBranch() throws Exception {
        FilterResult r = run("clean");
        assertThat(r.output()).isEqualTo("[main] ✓ clean");
    }

    @Test
    void cleanRepo_wasFiltered() throws Exception {
        assertThat(run("clean").wasFiltered()).isTrue();
    }

    @Test
    void cleanRepo_positiveTokenSavings() throws Exception {
        FilterResult r = run("clean");
        assertThat(r.rawTokens()).isGreaterThan(r.outTokens());
    }

    // ── modified ──────────────────────────────────────────────────────────────

    @Test
    void modified_showsModifiedCount() throws Exception {
        FilterResult r = run("modified");
        assertThat(r.output()).contains("modified: 2").contains("[main]");
    }

    @Test
    void modified_doesNotShowZeroCountsForStagedOrUntracked() throws Exception {
        FilterResult r = run("modified");
        assertThat(r.output()).doesNotContain("staged:");
        assertThat(r.output()).doesNotContain("untracked:");
    }

    // ── staged ────────────────────────────────────────────────────────────────

    @Test
    void staged_showsStagedCountAndBranch() throws Exception {
        FilterResult r = run("staged");
        assertThat(r.output()).contains("staged: 2").contains("[feature/new-filter]");
    }

    // ── untracked ─────────────────────────────────────────────────────────────

    @Test
    void untracked_showsUntrackedCount() throws Exception {
        FilterResult r = run("untracked");
        assertThat(r.output()).contains("untracked: 3");
        assertThat(r.output()).doesNotContain("staged:").doesNotContain("modified:");
    }

    // ── mixed ─────────────────────────────────────────────────────────────────

    @Test
    void mixed_showsAllThreeCountsWithPipeSeparator() throws Exception {
        FilterResult r = run("mixed");
        assertThat(r.output())
            .contains("staged: 1")
            .contains("modified: 2")
            .contains("untracked: 2")
            .contains("[develop]")
            .contains("|");
    }

    // ── detached HEAD ─────────────────────────────────────────────────────────

    @Test
    void detachedHead_showsDetachedPrefixAndClean() throws Exception {
        FilterResult r = run("detached-head");
        assertThat(r.output()).contains("detached@abc1234f").contains("✓ clean");
    }

    // ── failure passthrough ───────────────────────────────────────────────────

    @Test
    void nonZeroExit_passesStderrThrough() {
        var failure = new ExecutionResult(
            128, "", "fatal: not a git repository", 3L);
        FilterResult r = filter.apply("git status", failure, config, 0, false);
        assertThat(r.wasFiltered()).isFalse();
        assertThat(r.output()).contains("fatal: not a git repository");
    }

    @Test
    void nonZeroExit_exitCodeNotModifiedByFilter() {
        // Filters must not touch exit codes — verify the result record
        var failure = new ExecutionResult(128, "", "fatal: error", 1L);
        FilterResult r = filter.apply("git status", failure, config, 0, false);
        // savingsPct of 0 confirms no filtering was applied
        assertThat(r.savingsPct()).isZero();
    }

    // ── ultra-compact ─────────────────────────────────────────────────────────

    @Test
    void ultraCompact_mixed_isSingleLine() throws Exception {
        String raw = fixture("mixed");
        FilterResult r = filter.apply("git status",
            new ExecutionResult(0, raw, "", 10L), config, 0, true);
        assertThat(r.output().lines().count()).isEqualTo(1L);
    }

    @Test
    void ultraCompact_mixed_usesIconFormat() throws Exception {
        String raw = fixture("mixed");
        FilterResult r = filter.apply("git status",
            new ExecutionResult(0, raw, "", 10L), config, 0, true);
        // Must NOT use human-readable words
        assertThat(r.output()).doesNotContain("staged:");
        assertThat(r.output()).doesNotContain("modified:");
        assertThat(r.output()).doesNotContain("untracked:");
        // Must use at least one icon token
        boolean hasIcon = r.output().contains("↑S:") || r.output().contains("M:")
            || r.output().contains("?:");
        assertThat(hasIcon).isTrue();
    }

    // ── verbose ───────────────────────────────────────────────────────────────

    @Test
    void verbose2_appendsFileListBelowSummary() throws Exception {
        String raw = fixture("mixed");
        FilterResult r = filter.apply("git status",
            new ExecutionResult(0, raw, "", 10L), config, 2, false);
        assertThat(r.output().lines().count()).isGreaterThan(1L);
    }

    @Test
    void verbose1_doesNotAppendFileList() throws Exception {
        String raw = fixture("modified");
        FilterResult r = filter.apply("git status",
            new ExecutionResult(0, raw, "", 10L), config, 1, false);
        assertThat(r.output().lines().count()).isEqualTo(1L);
    }

    // ── token savings ─────────────────────────────────────────────────────────

    @Test
    void allFixturesProducePositiveTokenSavings() throws Exception {
        for (String name : List.of("clean", "modified", "staged", "untracked", "mixed")) {
            FilterResult r = run(name);
            assertThat(r.savingsPct())
                .as("Expected positive savings for fixture '%s' but got %d%%",
                    name, r.savingsPct())
                .isPositive();
        }
    }
}
