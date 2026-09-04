package com.condense.analytics;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.TrackingRepository;
import com.condense.core.Utf8WeightedTokenEstimator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GainRepositoryTest {

    @TempDir
    Path tempDir;

    private TrackingRepository tracking;
    private GainRepository repo;

    @BeforeEach
    void seed() {
        tracking = new TrackingRepository(new IsolatedPlatformDirs(
            tempDir.resolve("config"),
            tempDir.resolve("data")
        ));
        repo = new GainRepository(tracking);
        tracking.insert("git status", "abc123def456", "/tmp/proj", 600, 20, 40L);
        tracking.insert("cargo test", "abc123def456", "/tmp/proj", 5000, 400, 820L);
        tracking.insert("eslint src/", "abc123def456", "/tmp/proj", 1200, 80, 65L);
    }

    @AfterEach
    void tearDown() {
        tracking.close();
    }

    @Test
    void buildReport_global_hasPositiveTokensSaved() {
        GainReport report = repo.buildReport("global", 30, 5);
        assertThat(report).isNotNull();
        assertThat(report.totalCommands()).isGreaterThan(0);
        assertThat(report.tokensSaved()).isGreaterThan(0);
        assertThat(report.savingsPct()).isGreaterThan(0);
        assertThat(report.estimator()).isNotNull();
        assertThat(report.estimator().name()).isEqualTo(Utf8WeightedTokenEstimator.NAME);
        assertThat(report.estimator().reference()).isEqualTo(Utf8WeightedTokenEstimator.REFERENCE_TOKENIZER);
        assertThat(report.estimator().p95RelError()).isEqualTo(Utf8WeightedTokenEstimator.PUBLISHED_P95_REL_ERROR);
    }

    @Test
    void buildReport_scope_isPreserved() {
        GainReport report = repo.buildReport("project", 30, 5);
        assertThat(report.scope()).isEqualTo("project");
    }

    @Test
    void dailyStats_returnsNonEmptyList() {
        var stats = repo.dailyStats(30, "global");
        assertThat(stats).isNotEmpty();
    }

    @Test
    void topCommands_limitIsRespected() {
        var top = repo.topCommands(2, 30, "global");
        assertThat(top).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void recentCommands_limitIsRespected() {
        var recent = repo.recentCommands(2, "global");
        assertThat(recent).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void recentCommands_mostRecentFirst() {
        var recent = repo.recentCommands(10, "global");
        if (recent.size() >= 2) {
            assertThat(recent.get(0).ts()).isGreaterThanOrEqualTo(recent.get(1).ts());
        }
    }
}
