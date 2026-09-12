package com.condense.analytics;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.ProjectFingerprint;
import com.condense.core.TrackingRepository;
import com.condense.persist.CondenseClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrendAnalyticsTest {

    @TempDir
    Path tempDir;

    private TrackingRepository tracking;
    private TrendAnalytics trendAnalytics;

    @BeforeEach
    void setUp() {
        tracking = new TrackingRepository(new IsolatedPlatformDirs(
            tempDir.resolve("config"),
            tempDir.resolve("data")
        ));
        trendAnalytics = new TrendAnalytics(tracking);
    }

    @AfterEach
    void tearDown() {
        CondenseClock.restore();
        tracking.close();
    }

    @Test
    @DisplayName("Empty database returns requested number of weeks with zero metrics")
    void testEmptyDatabaseReturnsZeroWeeks() {
        TrendAnalytics.TrendReport report = trendAnalytics.buildTrendReport("global", 8);

        assertThat(report.weeksRequested()).isEqualTo(8);
        assertThat(report.totalCommands()).isZero();
        assertThat(report.totalRawTokens()).isZero();
        assertThat(report.totalFilteredTokens()).isZero();
        assertThat(report.totalTokensSaved()).isZero();
        assertThat(report.overallSavingsPct()).isZero();
        assertThat(report.overallCompressionRatio()).isEqualTo(1.0);
        assertThat(report.weeks()).hasSize(8);

        for (TrendAnalytics.WeekSummary week : report.weeks()) {
            assertThat(week.commands()).isZero();
            assertThat(week.rawTokens()).isZero();
            assertThat(week.filteredTokens()).isZero();
            assertThat(week.tokensSaved()).isZero();
            assertThat(week.savingsPct()).isZero();
            assertThat(week.compressionRatio()).isEqualTo(1.0);
            assertThat(week.week()).matches("\\d{4}-W\\d{2}");
        }
    }

    @Test
    @DisplayName("Continuous timeline gap-fills empty weeks between active weeks")
    void testContinuousTimelineWithGaps() {
        LocalDate pinnedDate = LocalDate.of(2026, 9, 12); // Saturday
        long nowSeconds = pinnedDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        CondenseClock.install(Clock.fixed(Instant.ofEpochSecond(nowSeconds), ZoneOffset.UTC));

        // Current week: 2026-W36
        // 2 weeks ago: 2026-W34
        // 4 weeks ago: 2026-W32
        long currentWeekTs = nowSeconds - 3600; // earlier today
        long twoWeeksAgoTs = nowSeconds - (14 * 86400L);
        long fourWeeksAgoTs = nowSeconds - (28 * 86400L);

        // Insert into week 36
        tracking.insertAt(currentWeekTs, "npm test", "proj1", "/tmp/proj1", 10000, 2000, 150L);
        // Insert into week 34
        tracking.insertAt(twoWeeksAgoTs, "cargo build", "proj1", "/tmp/proj1", 5000, 1000, 80L);
        // Insert into week 32
        tracking.insertAt(fourWeeksAgoTs, "git status", "proj1", "/tmp/proj1", 2000, 500, 20L);

        TrendAnalytics.TrendReport report = trendAnalytics.buildTrendReport("global", 6);

        assertThat(report.weeks()).hasSize(6);
        assertThat(report.totalCommands()).isEqualTo(3);
        assertThat(report.totalRawTokens()).isEqualTo(17000);
        assertThat(report.totalFilteredTokens()).isEqualTo(3500);
        assertThat(report.totalTokensSaved()).isEqualTo(13500);
        // 13500 / 17000 = 79.4%
        assertThat(report.overallSavingsPct()).isEqualTo(79.4);
        // 17000 / 3500 = 4.9x
        assertThat(report.overallCompressionRatio()).isEqualTo(4.9);

        // Check that empty weeks (like 33 and 35) are explicitly present
        List<TrendAnalytics.WeekSummary> weeks = report.weeks();
        TrendAnalytics.WeekSummary latestWeek = weeks.get(weeks.size() - 1);
        assertThat(latestWeek.week()).isEqualTo("2026-W36");
        assertThat(latestWeek.commands()).isEqualTo(1);
        assertThat(latestWeek.rawTokens()).isEqualTo(10000);
        assertThat(latestWeek.filteredTokens()).isEqualTo(2000);
        assertThat(latestWeek.tokensSaved()).isEqualTo(8000);
        assertThat(latestWeek.savingsPct()).isEqualTo(80.0);
        assertThat(latestWeek.compressionRatio()).isEqualTo(5.0);

        // Week 35 should be empty
        TrendAnalytics.WeekSummary week35 = weeks.get(weeks.size() - 2);
        assertThat(week35.week()).isEqualTo("2026-W35");
        assertThat(week35.commands()).isZero();
        assertThat(week35.rawTokens()).isZero();
        assertThat(week35.filteredTokens()).isZero();
        assertThat(week35.tokensSaved()).isZero();
        assertThat(week35.savingsPct()).isZero();
        assertThat(week35.compressionRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Project scope filtering isolates commands belonging to the current directory")
    void testProjectScopeFiltering() {
        LocalDate pinnedDate = LocalDate.of(2026, 9, 12);
        long nowSeconds = pinnedDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        CondenseClock.install(Clock.fixed(Instant.ofEpochSecond(nowSeconds), ZoneOffset.UTC));

        String myProjectHash = ProjectFingerprint.ofCurrentDir();
        String otherProjectHash = "other-project-hash-999";

        tracking.insertAt(nowSeconds - 100, "my-test", myProjectHash, "/my/cwd", 4000, 1000, 50L);
        tracking.insertAt(nowSeconds - 100, "other-test", otherProjectHash, "/other/cwd", 8000, 2000, 60L);

        TrendAnalytics.TrendReport globalReport = trendAnalytics.buildTrendReport("global", 4);
        assertThat(globalReport.totalCommands()).isEqualTo(2);
        assertThat(globalReport.totalRawTokens()).isEqualTo(12000);

        TrendAnalytics.TrendReport projectReport = trendAnalytics.buildTrendReport("project", 4);
        assertThat(projectReport.totalCommands()).isEqualTo(1);
        assertThat(projectReport.totalRawTokens()).isEqualTo(4000);
        assertThat(projectReport.totalFilteredTokens()).isEqualTo(1000);
        assertThat(projectReport.totalTokensSaved()).isEqualTo(3000);
    }

    @Test
    @DisplayName("Week formatting correctly handles year transition and week 00 vs week 01")
    void testWeekFormattingYearTransition() {
        // 2026-01-01 was a Thursday -> week 00 in SQLite %Y-W%W
        assertThat(TrendAnalytics.formatWeek(LocalDate.of(2026, 1, 1))).isEqualTo("2026-W00");
        assertThat(TrendAnalytics.formatWeek(LocalDate.of(2026, 1, 4))).isEqualTo("2026-W00");

        // First Monday was 2026-01-05 -> week 01
        assertThat(TrendAnalytics.formatWeek(LocalDate.of(2026, 1, 5))).isEqualTo("2026-W01");

        // Last week of 2025: 2025-12-28 (Sunday) was week 51, 2025-12-29 (Monday) was week 52
        assertThat(TrendAnalytics.formatWeek(LocalDate.of(2025, 12, 28))).isEqualTo("2025-W51");
        assertThat(TrendAnalytics.formatWeek(LocalDate.of(2025, 12, 29))).isEqualTo("2025-W52");
    }

    @Test
    @DisplayName("Week formatting matches SQLite strftime on arbitrary dates")
    void testWeekFormattingMatchesSqlite() {
        LocalDate date = LocalDate.of(2026, 9, 12);
        long ts = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        tracking.insertAt(ts, "probe", "proj", "/tmp", 100, 10, 5L);

        List<TrackingRepository.WeeklyStat> stats = tracking.queryWeekly(2, null);
        assertThat(stats).isNotEmpty();
        String sqliteWeek = stats.get(0).week();

        String javaWeek = TrendAnalytics.formatWeek(date);
        assertThat(javaWeek).isEqualTo(sqliteWeek);
    }
}
