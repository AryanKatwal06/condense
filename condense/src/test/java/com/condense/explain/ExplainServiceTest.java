package com.condense.explain;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.PassthroughStrategy;
import com.condense.corpus.CorpusRunner;
import com.condense.filter.git.GitPushFilter;
import com.condense.filter.git.GitStatusFilter;
import com.condense.filter.node.NpmInstallFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.pipeline.config.PipelineDecision;
import com.condense.trust.TrustTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplainServiceTest {

    @TempDir
    Path tempDir;

    private final ExplainService service = new ExplainService();
    private final CondenseConfig config = CondenseConfig.defaults();

    @Test
    void rejectedGitPushFiresGateAndStaysUnstamped() throws Exception {
        String fixture = CorpusRunner.loadFixture("fixtures/git-push/rejected.txt");
        ExecutionResult result = new ExecutionResult(1, fixture, "", 8L);
        ExplainReport report = service.explainStrategy(
            new GitPushFilter(), "git push", result, config, 0, false, 32, tempDir);
        assertThat(report.gate().fired()).isTrue();
        assertThat(report.stages()).isEmpty();
        assertThat(report.filteredOutput()).doesNotStartWith("condense[filtered]");
        assertThat(report.wasFiltered()).isFalse();
    }

    @Test
    void untrustedProjectOverrideIsSkippedAndNamed() throws Exception {
        Path project = tempDir.resolve("proj");
        Files.createDirectories(project.resolve(".condense"));
        Files.writeString(project.resolve(".condense/filters.toml"), """
            schema_version = 1
            [filters."git status"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """);
        Path configDir = tempDir.resolve("config");
        FilterOverrideLoader loader = TrustTestSupport.isolatedLoader(configDir);
        GitStatusFilter filter = new GitStatusFilter(loader);
        ExecutionResult result = new ExecutionResult(0, "On branch main\nnothing to commit\n", "", 4L);
        ExplainReport report = service.explainStrategy(
            filter, "git status", result, config, 0, false, 32, project);
        assertThat(report.tier()).isEqualTo(PipelineDecision.TIER_BUILTIN);
        assertThat(report.skippedTiers())
            .anyMatch(skip -> "project".equals(skip.tier()) && "untrusted".equals(skip.reason()));
        assertThat(report.source()).contains("git-status.toml");
    }

    @Test
    void trustedProjectOverrideWins() throws Exception {
        Path project = tempDir.resolve("trusted");
        Files.createDirectories(project.resolve(".condense"));
        Files.writeString(project.resolve(".condense/filters.toml"), """
            schema_version = 1
            [filters."git status"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """);
        Path configDir = tempDir.resolve("trusted-config");
        FilterOverrideLoader loader = TrustTestSupport.trustedLoader(configDir, project);
        GitStatusFilter filter = new GitStatusFilter(loader);
        ExecutionResult result = new ExecutionResult(0, "On branch main\nnothing to commit\n", "", 4L);
        ExplainReport report = service.explainStrategy(
            filter, "git status", result, config, 0, false, 32, project);
        assertThat(report.tier()).isEqualTo(PipelineDecision.TIER_PROJECT);
        assertThat(report.source()).contains("filters.toml");
    }

    @Test
    void unmatchedCommandIsPassthrough() {
        ExecutionResult result = new ExecutionResult(0, "hello\n", "", 1L);
        ExplainReport report = service.explainStrategy(
            new PassthroughStrategy(), "unknown-tool", result, config, 0, false, 32, tempDir);
        assertThat(report.tier()).isEqualTo(PipelineDecision.TIER_PASSTHROUGH);
        assertThat(report.pipelineMode()).isEqualTo("live_raw");
        assertThat(report.stages()).isEmpty();
        assertThat(report.wasFiltered()).isFalse();
    }

    @Test
    void droppedLimitZeroKeepsCountsOnly() throws Exception {
        NpmInstallFilter filter = new NpmInstallFilter();
        ExecutionResult result = new ExecutionResult(0,
            "added 12 packages in 3s\nfound 3 vulnerabilities (1 critical)\n", "", 5L);
        ExplainReport report = service.explainStrategy(
            filter, "npm install", result, config, 0, false, 0, tempDir);
        assertThat(report.pipelineMode()).isEqualTo("stream");
        assertThat(report.stages()).extracting(ExplainReport.Stage::id)
            .contains("ansi_strip", "npm_install_summary");
        assertThat(report.stages())
            .filteredOn(stage -> "npm_install_summary".equals(stage.id()))
            .extracting(ExplainReport.Stage::streamability)
            .containsExactly("order_local");
        assertThat(report.stages())
            .allMatch(stage -> stage.droppedSample().isEmpty());
        assertThat(report.stages().stream().mapToInt(ExplainReport.Stage::droppedLines).sum())
            .isGreaterThanOrEqualTo(0);
    }

    @Test
    void oversizedInputIsRefused() throws Exception {
        Path huge = tempDir.resolve("huge.txt");
        Files.write(huge, new byte[ExplainService.MAX_INPUT_BYTES + 1]);
        assertThatThrownBy(() -> ExplainService.readBounded(huge))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("capture cap");
    }
}
