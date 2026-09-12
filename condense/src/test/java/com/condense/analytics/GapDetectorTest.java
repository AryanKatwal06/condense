package com.condense.analytics;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.TrackingRepository;
import com.condense.persist.CondenseClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GapDetectorTest {

    @TempDir
    Path tempDir;

    private TrackingRepository tracking;
    private GapDetector detector;
    private long nowSeconds;

    @BeforeEach
    void setUp() {
        tracking = new TrackingRepository(new IsolatedPlatformDirs(
            tempDir.resolve("config"),
            tempDir.resolve("data")
        ));
        detector = new GapDetector(tracking);

        LocalDate pinned = LocalDate.of(2026, 9, 12);
        nowSeconds = pinned.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        CondenseClock.install(Clock.fixed(Instant.ofEpochSecond(nowSeconds), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        CondenseClock.restore();
        tracking.close();
    }

    @Test
    @DisplayName("High-waste command with <10% savings is identified as a gap candidate")
    void testHighWasteCandidateIdentified() {
        // 5000 raw, 4800 out -> 4% savings (< 10%) and raw > 100
        tracking.insertAt(nowSeconds - 100, "mvn test -Dtest=Foo", "proj1", "/tmp/p1", 5000, 4800, 200L);

        List<GapDetector.GapCandidate> gaps = detector.findGaps("global", 30, 10);
        assertThat(gaps).hasSize(1);
        GapDetector.GapCandidate candidate = gaps.get(0);
        assertThat(candidate.commandPrefix()).isEqualTo("mvn test");
        assertThat(candidate.invocations()).isEqualTo(1);
        assertThat(candidate.totalRawTokens()).isEqualTo(5000);
        assertThat(candidate.totalFilteredTokens()).isEqualTo(4800);
        assertThat(candidate.wastedTokens()).isEqualTo(4800);
        assertThat(candidate.savingsPct()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("Commands with <=100 raw tokens are ignored even if 0% savings")
    void testPassthroughUnder100TokensIgnored() {
        // 80 raw, 80 out -> 0% savings, but raw <= 100
        tracking.insertAt(nowSeconds - 100, "echo hello world", "proj1", "/tmp/p1", 80, 80, 5L);
        // exactly 100 raw, 100 out -> raw <= 100
        tracking.insertAt(nowSeconds - 200, "git branch", "proj1", "/tmp/p1", 100, 100, 10L);

        List<GapDetector.GapCandidate> gaps = detector.findGaps("global", 30, 10);
        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("Commands with >=10% token savings are ignored")
    void testHighSavingsAbove10PercentIgnored() {
        // 1000 raw, 800 out -> 20% savings (>= 10%)
        tracking.insertAt(nowSeconds - 100, "git status --short", "proj1", "/tmp/p1", 1000, 800, 15L);
        // 1000 raw, 900 out -> exactly 10% savings (>= 10%)
        tracking.insertAt(nowSeconds - 200, "npm test", "proj1", "/tmp/p1", 1000, 900, 50L);

        List<GapDetector.GapCandidate> gaps = detector.findGaps("global", 30, 10);
        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("Subcommands with same prefix are aggregated together")
    void testPrefixGroupingAggregatesSubcommands() {
        // Two mvn test commands with different flags
        tracking.insertAt(nowSeconds - 100, "mvn test -Dtest=TestA", "proj1", "/tmp/p1", 6000, 5800, 100L);
        tracking.insertAt(nowSeconds - 200, "mvn test -Dtest=TestB", "proj1", "/tmp/p1", 4000, 3900, 80L);

        List<GapDetector.GapCandidate> gaps = detector.findGaps("global", 30, 10);
        assertThat(gaps).hasSize(1);
        GapDetector.GapCandidate candidate = gaps.get(0);
        assertThat(candidate.commandPrefix()).isEqualTo("mvn test");
        assertThat(candidate.invocations()).isEqualTo(2);
        assertThat(candidate.totalRawTokens()).isEqualTo(10000);
        assertThat(candidate.totalFilteredTokens()).isEqualTo(9700);
        assertThat(candidate.wastedTokens()).isEqualTo(9700);
        assertThat(candidate.savingsPct()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("Single-token commands extract prefix correctly")
    void testSingleTokenPrefixSupported() {
        // Single word command
        tracking.insertAt(nowSeconds - 100, "pytest", "proj1", "/tmp/p1", 2000, 1950, 150L);

        List<GapDetector.GapCandidate> gaps = detector.findGaps("global", 30, 10);
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).commandPrefix()).isEqualTo("pytest");
    }

    @Test
    @DisplayName("extractPrefix helper handles varying whitespace and token counts")
    void testExtractPrefixHelper() {
        assertThat(GapDetector.extractPrefix("git   log   -n 5")).isEqualTo("git log");
        assertThat(GapDetector.extractPrefix("  cargo build  ")).isEqualTo("cargo build");
        assertThat(GapDetector.extractPrefix("make")).isEqualTo("make");
        assertThat(GapDetector.extractPrefix("")).isEqualTo("");
        assertThat(GapDetector.extractPrefix(null)).isEqualTo("");
    }

    @Test
    @DisplayName("Candidates are sorted by total wasted tokens descending")
    void testSortingByWastedTokensDescending() {
        // Less wasted tokens: 1000 raw, 950 out -> wasted: 950
        tracking.insertAt(nowSeconds - 100, "gradle build", "proj1", "/tmp/p1", 1000, 950, 50L);
        // More wasted tokens: 8000 raw, 7800 out -> wasted: 7800
        tracking.insertAt(nowSeconds - 200, "mvn test", "proj1", "/tmp/p1", 8000, 7800, 100L);

        List<GapDetector.GapCandidate> gaps = detector.findGaps("global", 30, 10);
        assertThat(gaps).hasSize(2);
        assertThat(gaps.get(0).commandPrefix()).isEqualTo("mvn test");
        assertThat(gaps.get(1).commandPrefix()).isEqualTo("gradle build");
    }

    @Test
    @DisplayName("Project scope filters out commands from other projects")
    void testProjectScopeIsolation() {
        // Current directory project hash
        String currentProject = com.condense.core.ProjectFingerprint.ofCurrentDir();
        tracking.insertAt(nowSeconds - 100, "mvn test", currentProject, "/tmp/here", 5000, 4900, 100L);
        tracking.insertAt(nowSeconds - 200, "gradle build", "other-project-hash", "/tmp/there", 8000, 7800, 100L);

        List<GapDetector.GapCandidate> gaps = detector.findGaps("project", 30, 10);
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).commandPrefix()).isEqualTo("mvn test");
    }

    @Test
    @DisplayName("Limit parameter caps the number of returned candidates")
    void testLimitParameter() {
        tracking.insertAt(nowSeconds - 100, "cmd1 sub", "p", "/tmp", 2000, 1900, 10L);
        tracking.insertAt(nowSeconds - 200, "cmd2 sub", "p", "/tmp", 3000, 2900, 10L);
        tracking.insertAt(nowSeconds - 300, "cmd3 sub", "p", "/tmp", 4000, 3900, 10L);

        List<GapDetector.GapCandidate> gaps = detector.findGaps("global", 30, 2);
        assertThat(gaps).hasSize(2);
    }

    @Test
    @DisplayName("Empty database returns empty list with zero exceptions")
    void testEmptyDatabaseResilience() {
        List<GapDetector.GapCandidate> gaps = detector.findGaps("global", 30, 10);
        assertThat(gaps).isEmpty();

        // Null detector resilience
        GapDetector nullDetector = new GapDetector(null);
        assertThat(nullDetector.findGaps("global", 30, 10)).isEmpty();
    }
}
