package com.condense.analytics;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.TrackingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GainCsvFormatTest {

    @TempDir
    Path tempDir;

    private TrackingRepository tracking;
    private GainRepository gainRepo;
    private ByteArrayOutputStream outStream;
    private PrintStream originalOut;

    @BeforeEach
    void setUp() {
        tracking = new TrackingRepository(new IsolatedPlatformDirs(
            tempDir.resolve("config"),
            tempDir.resolve("data")
        ));
        gainRepo = new GainRepository(tracking);
        tracking.insert("git status", "proj123", "/tmp/proj", 600, 20, 40L);
        tracking.insert("cargo test", "proj123", "/tmp/proj", 5000, 400, 820L);
        tracking.insert("eslint src/", "proj123", "/tmp/proj", 1200, 80, 65L);
        tracking.insert("git commit -m \"fix, typo\"", "proj123", "/tmp/proj", 150, 10, 15L);

        outStream = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(outStream));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        tracking.close();
    }

    @Test
    @DisplayName("Summary mode emits RFC-4180 CSV with metric,value header and cost fields")
    void testSummaryCsv() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.format = "csv";
        cmd.run();

        String output = outStream.toString().trim();
        List<String> lines = output.lines().toList();

        assertThat(lines.get(0)).isEqualTo("metric,value");
        assertThat(output).contains("total_commands,4");
        assertThat(output).contains("input_tokens,6950");
        assertThat(output).contains("output_tokens,510");
        assertThat(output).contains("tokens_saved,6440");
        assertThat(output).contains("savings_pct,92");
        assertThat(output).contains("cost_model,claude-3-5-sonnet-20241022");
        assertThat(output).contains("cost_provider,Anthropic");
        assertThat(output).contains("cost_currency,USD");
        assertThat(output).contains("cost_input_rate_per_m,3.00");
        assertThat(output).contains("cost_output_rate_per_m,15.00");
        assertThat(output).contains("estimated_usd_saved,0.019320");
        assertThat(output).contains("history_status,homogeneous");
    }

    @Test
    @DisplayName("Daily mode emits date,raw_tokens,filtered_tokens,saved_tokens,est_usd_saved,commands header")
    void testDailyCsv() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.format = "csv";
        cmd.daily = true;
        cmd.run();

        String output = outStream.toString().trim();
        List<String> lines = output.lines().toList();

        assertThat(lines.get(0)).isEqualTo("date,raw_tokens,filtered_tokens,saved_tokens,est_usd_saved,commands");
        assertThat(lines.size()).isGreaterThan(1);
        assertThat(lines.get(1)).contains(",6950,510,6440,0.019320,4");
    }

    @Test
    @DisplayName("Weekly mode emits week,raw_tokens,filtered_tokens,saved_tokens,est_usd_saved,commands header")
    void testWeeklyCsv() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.format = "csv";
        cmd.weekly = true;
        cmd.run();

        String output = outStream.toString().trim();
        List<String> lines = output.lines().toList();

        assertThat(lines.get(0)).isEqualTo("week,raw_tokens,filtered_tokens,saved_tokens,est_usd_saved,commands");
        assertThat(lines.size()).isGreaterThan(1);
        assertThat(lines.get(1)).contains(",6950,510,6440,0.019320,4");
    }

    @Test
    @DisplayName("Top mode emits command,raw_tokens,filtered_tokens,saved_tokens,est_usd_saved,count header")
    void testTopCsv() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.format = "csv";
        cmd.topFlag = 5;
        cmd.run();

        String output = outStream.toString().trim();
        List<String> lines = output.lines().toList();

        assertThat(lines.get(0)).isEqualTo("command,raw_tokens,filtered_tokens,saved_tokens,est_usd_saved,count");
        assertThat(output).contains("cargo test,5000,400,4600,0.013800,1");
        // Check escaping of command with comma and quotes
        assertThat(output).contains("\"git commit -m \"\"fix, typo\"\"\",150,10,140,0.000420,1");
    }

    @Test
    @DisplayName("History mode emits timestamp,command,project,raw_tokens,filtered_tokens,saved_tokens,est_usd_saved,duration_ms")
    void testHistoryCsv() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.format = "csv";
        cmd.historyFlag = 5;
        cmd.run();

        String output = outStream.toString().trim();
        List<String> lines = output.lines().toList();

        assertThat(lines.get(0)).isEqualTo("timestamp,command,project,raw_tokens,filtered_tokens,saved_tokens,est_usd_saved,duration_ms");
        assertThat(output).contains("\"git commit -m \"\"fix, typo\"\"\"");
        assertThat(output).contains(",150,10,140,0.000420,15");
    }
}
