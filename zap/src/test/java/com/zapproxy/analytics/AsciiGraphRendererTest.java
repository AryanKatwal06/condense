package com.zapproxy.analytics;

import com.zapproxy.core.TrackingRepository.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AsciiGraphRendererTest {

    // ── Efficiency meter ──────────────────────────────────────────────────────

    @Test
    void meterAt0Pct_allEmpty() {
        String meter = AsciiGraphRenderer.efficiencyMeter(0);
        assertThat(meter).hasSize(24);
        assertThat(meter).doesNotContain("█").doesNotContain("▌");
        assertThat(meter).contains("░");
    }

    @Test
    void meterAt100Pct_allFull() {
        String meter = AsciiGraphRenderer.efficiencyMeter(100);
        assertThat(meter).hasSize(24);
        assertThat(meter).doesNotContain("░");
        assertThat(meter).contains("█");
    }

    @Test
    void meterAt50Pct_halfFull() {
        String meter = AsciiGraphRenderer.efficiencyMeter(50);
        assertThat(meter).hasSize(24);
        assertThat(meter).contains("█");
        assertThat(meter).contains("░");
    }

    @Test
    void meterAlwaysExactly24Chars() {
        for (int i = 0; i <= 100; i += 10) {
            assertThat(AsciiGraphRenderer.efficiencyMeter(i))
                .as("Meter at %d%% must be 24 chars", i)
                .hasSize(24);
        }
    }

    @Test
    void meterClampsAbove100() {
        assertThat(AsciiGraphRenderer.efficiencyMeter(150)).hasSize(24);
    }

    @Test
    void meterClampsBelow0() {
        assertThat(AsciiGraphRenderer.efficiencyMeter(-10)).hasSize(24);
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    @Test
    void renderSummary_containsAllRequiredFields() {
        GainReport report = new GainReport(
            "global", 30, 127L, 48302L, 9142L, 39160L, 81, 612L, 4L, List.of(), List.of());
        String output = AsciiGraphRenderer.renderSummary(report);

        assertThat(output).contains("Total commands");
        assertThat(output).contains("127");
        assertThat(output).contains("Tokens saved");
        assertThat(output).contains("Efficiency meter");
        assertThat(output).contains("Global Scope");
        assertThat(output).contains("81");
    }

    @Test
    void renderSummary_containsDivider() {
        GainReport report = new GainReport(
            "global", 30, 0L, 0L, 0L, 0L, 0, 0L, 0L, List.of(), List.of());
        assertThat(AsciiGraphRenderer.renderSummary(report)).contains("════");
    }

    // ── History ───────────────────────────────────────────────────────────────

    @Test
    void renderHistory_highSavingsGetsTriangleIcon() {
        var rows = List.of(new RecentCommand(1000L, "git status", 600, 12, 40L));
        String output = AsciiGraphRenderer.renderHistory(rows);
        assertThat(output).contains("▲"); // 98% savings
        assertThat(output).contains("git status");
    }

    @Test
    void renderHistory_lowSavingsGetsCircleIcon() {
        var rows = List.of(new RecentCommand(1000L, "echo hello", 3, 3, 2L));
        String output = AsciiGraphRenderer.renderHistory(rows);
        assertThat(output).contains("•"); // 0% savings
    }

    @Test
    void renderHistory_emptyReturnsGuidanceMessage() {
        assertThat(AsciiGraphRenderer.renderHistory(List.of()))
            .contains("No command history");
    }

    // ── Graph ─────────────────────────────────────────────────────────────────

    @Test
    void renderGraph_emptyDataReturnsGuidanceMessage() {
        assertThat(AsciiGraphRenderer.renderGraph(List.of(), 30))
            .contains("No data");
    }

    @Test
    void renderGraph_withDataContainsBars() {
        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String yesterday = java.time.LocalDate.now().minusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        var stats = List.of(
            new DailyStat(yesterday, 5L, 5000L, 500L),
            new DailyStat(today, 3L, 3000L, 300L)
        );
        String output = AsciiGraphRenderer.renderGraph(stats, 30);
        assertThat(output).contains("#");
        assertThat(output).contains("|");
    }

    // ── Top commands ──────────────────────────────────────────────────────────

    @Test
    void renderTopCommands_numberedCorrectly() {
        var cmds = List.of(
            new TopCommand("git status", 127L, 48000L, 9000L),
            new TopCommand("cargo test",  12L,  8000L, 2000L)
        );
        String output = AsciiGraphRenderer.renderTopCommands(cmds);
        assertThat(output).contains("1");
        assertThat(output).contains("2");
        assertThat(output).contains("git status");
        assertThat(output).contains("cargo test");
    }
}
