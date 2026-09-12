package com.condense.analytics;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.TrackingRepository;
import com.condense.persist.CondenseClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GainCommandGapsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private TrackingRepository tracking;
    private GainRepository gainRepo;
    private GapDetector gapDetector;
    private ByteArrayOutputStream outStream;
    private ByteArrayOutputStream errStream;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private long nowSeconds;

    @BeforeEach
    void setUp() {
        tracking = new TrackingRepository(new IsolatedPlatformDirs(
            tempDir.resolve("config"),
            tempDir.resolve("data")
        ));
        gainRepo = new GainRepository(tracking);
        gapDetector = new GapDetector(tracking);

        LocalDate pinned = LocalDate.of(2026, 9, 12);
        nowSeconds = pinned.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        CondenseClock.install(Clock.fixed(Instant.ofEpochSecond(nowSeconds), ZoneOffset.UTC));

        outStream = new ByteArrayOutputStream();
        errStream = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outStream));
        System.setErr(new PrintStream(errStream));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        CondenseClock.restore();
        tracking.close();
    }

    private void seedTestData() {
        // Gap candidates
        tracking.insertAt(nowSeconds - 100, "mvn test -Dtest=TestA", "proj1", "/tmp/p1", 10000, 9500, 200L);
        tracking.insertAt(nowSeconds - 200, "mvn test -Dtest=TestB", "proj1", "/tmp/p1", 15000, 14000, 300L);
        tracking.insertAt(nowSeconds - 300, "docker build -t test .", "proj1", "/tmp/p1", 5000, 4800, 1500L);

        // Filtered successfully (>10% savings) -> should NOT be a gap candidate
        tracking.insertAt(nowSeconds - 400, "cargo test", "proj1", "/tmp/p1", 20000, 2000, 400L);

        // Low volume (<= 100 raw tokens) -> should NOT be a gap candidate
        tracking.insertAt(nowSeconds - 500, "git status", "proj1", "/tmp/p1", 50, 48, 10L);
    }

    @Test
    @DisplayName("GainCommand parses --gaps flag")
    void testCliParsing() {
        GainCommand cmd = new GainCommand();
        new CommandLine(cmd).parseArgs("--gaps");
        assertThat(cmd.gaps).isTrue();
    }

    @Test
    @DisplayName("--gaps prints ASCII table with low-savings commands grouped by prefix")
    void testGapsTableTextOutput() {
        seedTestData();

        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.gapDetector = gapDetector;
        cmd.gaps = true;
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("Gap Candidates (Low Savings & High Volume)");
        assertThat(out).contains("Command Prefix");
        assertThat(out).contains("Uses");
        assertThat(out).contains("Raw Tokens");
        assertThat(out).contains("Wasted Tokens");
        assertThat(out).contains("Savings");

        assertThat(out).contains("mvn test");
        assertThat(out).contains("docker build");
        assertThat(out).doesNotContain("cargo test");
        assertThat(out).doesNotContain("git status");
    }

    @Test
    @DisplayName("--gaps prints helpful message when no gap candidates exist")
    void testGapsEmptyOutput() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.gapDetector = gapDetector;
        cmd.gaps = true;
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("No gap candidates found. Commands are either filtering effectively (>10% savings) or output volume is small (<=100 tokens).");
    }

    @Test
    @DisplayName("--gaps --format json emits valid GapCandidate JSON array")
    void testGapsJsonOutput() throws Exception {
        seedTestData();

        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.gapDetector = gapDetector;
        cmd.gaps = true;
        cmd.format = "json";
        cmd.run();

        String out = outStream.toString();
        JsonNode root = JSON.readTree(out);
        assertThat(root.isArray()).isTrue();
        assertThat(root).hasSize(2);

        JsonNode top = root.get(0);
        assertThat(top.get("command_prefix").asText()).isEqualTo("mvn test");
        assertThat(top.get("invocations").asLong()).isEqualTo(2);
        assertThat(top.get("total_raw_tokens").asLong()).isEqualTo(25000);
        assertThat(top.get("total_filtered_tokens").asLong()).isEqualTo(23500);
        assertThat(top.get("wasted_tokens").asLong()).isEqualTo(23500);
        assertThat(top.get("savings_pct").asDouble()).isEqualTo(6.0);

        JsonNode second = root.get(1);
        assertThat(second.get("command_prefix").asText()).isEqualTo("docker build");
        assertThat(second.get("invocations").asLong()).isEqualTo(1);
        assertThat(second.get("total_raw_tokens").asLong()).isEqualTo(5000);
        assertThat(second.get("total_filtered_tokens").asLong()).isEqualTo(4800);
        assertThat(second.get("wasted_tokens").asLong()).isEqualTo(4800);
        assertThat(second.get("savings_pct").asDouble()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("--gaps --format csv emits valid RFC-4180 CSV with headers")
    void testGapsCsvOutput() {
        seedTestData();

        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.gapDetector = gapDetector;
        cmd.gaps = true;
        cmd.format = "csv";
        cmd.run();

        String out = outStream.toString().trim();
        List<String> lines = out.lines().toList();
        assertThat(lines.get(0)).isEqualTo("command_prefix,invocations,raw_tokens,filtered_tokens,wasted_tokens,savings_pct");
        assertThat(lines.size()).isEqualTo(3); // Header + 2 rows
        assertThat(lines.get(1)).isEqualTo("mvn test,2,25000,23500,23500,6.0");
        assertThat(lines.get(2)).isEqualTo("docker build,1,5000,4800,4800,4.0");
    }

    @Test
    @DisplayName("--gaps --top N limits output to top N candidates")
    void testGapsTopLimit() {
        seedTestData();

        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.gapDetector = gapDetector;
        new CommandLine(cmd).parseArgs("--gaps", "--top", "1");
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("mvn test");
        assertThat(out).doesNotContain("docker build");
    }

    @Test
    @DisplayName("--gaps --top N --format json limits JSON array size")
    void testGapsJsonTopLimit() throws Exception {
        seedTestData();

        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.gapDetector = gapDetector;
        new CommandLine(cmd).parseArgs("--gaps", "--top", "1", "--format", "json");
        cmd.run();

        String out = outStream.toString();
        JsonNode root = JSON.readTree(out);
        assertThat(root.isArray()).isTrue();
        assertThat(root).hasSize(1);
        assertThat(root.get(0).get("command_prefix").asText()).isEqualTo("mvn test");
    }

    @Test
    @DisplayName("--gaps respects --since window")
    void testGapsSinceWindow() {
        // Insert old command 40 days ago
        tracking.insertAt(nowSeconds - (40 * 86400L), "old tool", "proj1", "/tmp/p1", 8000, 7800, 100L);
        // Insert recent command 2 days ago
        tracking.insertAt(nowSeconds - (2 * 86400L), "new tool", "proj1", "/tmp/p1", 8000, 7800, 100L);

        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.gapDetector = gapDetector;
        new CommandLine(cmd).parseArgs("--gaps", "--since", "10");
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("new tool");
        assertThat(out).doesNotContain("old tool");
    }

    @Test
    @DisplayName("AsciiGraphRenderer.renderGapTable handles null and empty candidates gracefully")
    void testAsciiRendererDirectly() {
        assertThat(AsciiGraphRenderer.renderGapTable(null))
            .contains("No gap candidates found");
        assertThat(AsciiGraphRenderer.renderGapTable(List.of()))
            .contains("No gap candidates found");

        String rendered = AsciiGraphRenderer.renderGapTable(List.of(
            new GapDetector.GapCandidate("pytest -k", 10, 50000, 48000, 48000, 4.0)
        ));
        assertThat(rendered).contains("Gap Candidates (Low Savings & High Volume)");
        assertThat(rendered).contains("pytest -k");
        assertThat(rendered).contains("48,000");
    }
}
