package com.condense.analytics;

import com.condense.core.TrackingRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GainRepositoryTest {

    @Inject
    GainRepository repo;

    @Inject
    TrackingRepository tracking;

    @BeforeEach
    void seed() {
        // Insert some test rows
        tracking.insert("git status", "abc123def456", "/tmp/proj", 600, 20, 40L);
        tracking.insert("cargo test", "abc123def456", "/tmp/proj", 5000, 400, 820L);
        tracking.insert("eslint src/", "abc123def456", "/tmp/proj", 1200, 80, 65L);
    }

    @Test
    @Order(1)
    void buildReport_global_hasPositiveTokensSaved() {
        GainReport report = repo.buildReport("global", 30, 5);
        assertThat(report).isNotNull();
        assertThat(report.totalCommands()).isGreaterThan(0);
        assertThat(report.tokensSaved()).isGreaterThan(0);
        assertThat(report.savingsPct()).isGreaterThan(0);
    }

    @Test
    @Order(2)
    void buildReport_scope_isPreserved() {
        GainReport report = repo.buildReport("project", 30, 5);
        assertThat(report.scope()).isEqualTo("project");
    }

    @Test
    @Order(3)
    void dailyStats_returnsNonEmptyList() {
        var stats = repo.dailyStats(30, "global");
        assertThat(stats).isNotEmpty();
    }

    @Test
    @Order(4)
    void topCommands_limitIsRespected() {
        var top = repo.topCommands(2, 30, "global");
        assertThat(top).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @Order(5)
    void recentCommands_limitIsRespected() {
        var recent = repo.recentCommands(2, "global");
        assertThat(recent).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @Order(6)
    void recentCommands_mostRecentFirst() {
        var recent = repo.recentCommands(10, "global");
        if (recent.size() >= 2) {
            assertThat(recent.get(0).ts()).isGreaterThanOrEqualTo(recent.get(1).ts());
        }
    }
}
