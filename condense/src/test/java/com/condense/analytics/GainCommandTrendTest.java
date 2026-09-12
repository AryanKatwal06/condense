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

class GainCommandTrendTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private TrackingRepository tracking;
    private GainRepository gainRepo;
    private TrendAnalytics trendAnalytics;
    private ByteArrayOutputStream outStream;
    private ByteArrayOutputStream errStream;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        tracking = new TrackingRepository(new IsolatedPlatformDirs(
            tempDir.resolve("config"),
            tempDir.resolve("data")
        ));
        gainRepo = new GainRepository(tracking);
        trendAnalytics = new TrendAnalytics(tracking);

        LocalDate pinned = LocalDate.of(2026, 9, 12);
        long nowSeconds = pinned.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        CondenseClock.install(Clock.fixed(Instant.ofEpochSecond(nowSeconds), ZoneOffset.UTC));

        // Seed some data across weeks
        tracking.insertAt(nowSeconds - 1000, "npm test", "proj1", "/tmp/p1", 8000, 1600, 100L);
        tracking.insertAt(nowSeconds - (7 * 86400L), "cargo build", "proj1", "/tmp/p1", 4000, 800, 50L);

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

    @Test
    @DisplayName("GainCommand parses --trend flag")
    void testCliParsing() {
        GainCommand cmd = new GainCommand();
        new CommandLine(cmd).parseArgs("--trend");
        assertThat(cmd.trend).isTrue();
    }

    @Test
    @DisplayName("--trend prints ASCII table with 8 weeks and total row")
    void testTrendTableTextOutput() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.trendAnalytics = trendAnalytics;
        cmd.trend = true;
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("Week");
        assertThat(out).contains("Cmds");
        assertThat(out).contains("Raw");
        assertThat(out).contains("Filtered");
        assertThat(out).contains("Saved");
        assertThat(out).contains("Ratio");
        assertThat(out).contains("Total (8w)");
        assertThat(out).contains("2026-W36");
        assertThat(out).contains("2026-W35");
    }

    @Test
    @DisplayName("--trend --format json emits valid TrendReport JSON")
    void testTrendJsonOutput() throws Exception {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.trendAnalytics = trendAnalytics;
        cmd.trend = true;
        cmd.format = "json";
        cmd.run();

        String out = outStream.toString();
        JsonNode root = JSON.readTree(out);
        assertThat(root.get("weeks_requested").asInt()).isEqualTo(8);
        assertThat(root.get("total_commands").asLong()).isEqualTo(2);
        assertThat(root.get("total_raw_tokens").asLong()).isEqualTo(12000);
        assertThat(root.get("total_filtered_tokens").asLong()).isEqualTo(2400);
        assertThat(root.get("total_tokens_saved").asLong()).isEqualTo(9600);
        assertThat(root.get("overall_savings_pct").asDouble()).isEqualTo(80.0);
        assertThat(root.get("overall_compression_ratio").asDouble()).isEqualTo(5.0);

        JsonNode weeks = root.get("weeks");
        assertThat(weeks.isArray()).isTrue();
        assertThat(weeks).hasSize(8);

        JsonNode latest = weeks.get(weeks.size() - 1);
        assertThat(latest.get("week").asText()).isEqualTo("2026-W36");
        assertThat(latest.get("commands").asLong()).isEqualTo(1);
        assertThat(latest.get("raw_tokens").asLong()).isEqualTo(8000);
        assertThat(latest.get("filtered_tokens").asLong()).isEqualTo(1600);
        assertThat(latest.get("tokens_saved").asLong()).isEqualTo(6400);
        assertThat(latest.get("savings_pct").asDouble()).isEqualTo(80.0);
        assertThat(latest.get("compression_ratio").asDouble()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("--trend --format csv emits valid CSV with headers")
    void testTrendCsvOutput() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.trendAnalytics = trendAnalytics;
        cmd.trend = true;
        cmd.format = "csv";
        cmd.run();

        String out = outStream.toString().trim();
        List<String> lines = out.lines().toList();
        assertThat(lines.get(0)).isEqualTo("week,commands,raw_tokens,filtered_tokens,saved_tokens,savings_pct,compression_ratio");
        assertThat(lines.size()).isEqualTo(9); // 1 header + 8 weeks
        assertThat(lines.get(lines.size() - 1)).startsWith("2026-W36,1,8000,1600,6400,80.0,5.0");
    }

    @Test
    @DisplayName("--trend adapts week count when --since is specified")
    void testTrendWithSince() throws Exception {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.trendAnalytics = trendAnalytics;
        cmd.trend = true;
        cmd.since = 14;
        cmd.format = "json";
        cmd.run();

        String out = outStream.toString();
        JsonNode root = JSON.readTree(out);
        assertThat(root.get("weeks_requested").asInt()).isEqualTo(3); // 14 / 7 + 1 = 3
        assertThat(root.get("weeks")).hasSize(3);
    }

    @Test
    @DisplayName("Picocli command line parsing with --trend produces 8 weeks without --since")
    void testPicocliTrendParsingProducesEightWeeks() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.trendAnalytics = trendAnalytics;
        new CommandLine(cmd).parseArgs("--trend");
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("Total (8w)");
    }

    @Test
    @DisplayName("Picocli command line parsing with --trend and --since adapts week count")
    void testPicocliTrendParsingWithSince() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.trendAnalytics = trendAnalytics;
        new CommandLine(cmd).parseArgs("--trend", "--since", "14");
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("Total (3w)");
    }
}
