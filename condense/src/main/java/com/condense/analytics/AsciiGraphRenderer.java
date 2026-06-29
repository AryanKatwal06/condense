package com.condense.analytics;

import com.condense.core.TrackingRepository.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders token savings data as terminal-friendly ASCII/Unicode art.
 *
 * <p>Two visual systems:
 * <ul>
 *   <li><strong>Efficiency meter</strong>: Unicode block chars (█ ▌ ░) — used in
 *       the default summary and history rows</li>
 *   <li><strong>30-day bar chart</strong>: ASCII {@code #} chars — used in
 *       {@code --graph} output for maximum terminal compatibility</li>
 * </ul>
 *
 * <p>This class is stateless and cannot be instantiated.
 */
public final class AsciiGraphRenderer {

    private AsciiGraphRenderer() {}

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String FULL_BLOCK  = "█";
    private static final String HALF_BLOCK  = "▌";
    private static final String EMPTY_BLOCK = "░";
    private static final int    METER_WIDTH = 24;

    private static final String DIVIDER =
        "════════════════════════════════════════════════════════════";

    // ── Default summary ───────────────────────────────────────────────────────

    /**
     * Renders the default {@code condense gain} output (no flags).
     *
     * <pre>
     * Condense Token Savings (Global Scope)
     * ════════════════════════════════════════════════════════════
     *
     * Total commands:    127
     * Input tokens:      48,302
     * Output tokens:      9,142
     * Tokens saved:      39,160 (81.1%)
     * Total exec time:      612ms (avg 4ms)
     * Efficiency meter: ████████████████████░░░░ 81.1%
     * </pre>
     */
    public static String renderSummary(GainReport report) {
        String title = "Condense Token Savings ("
            + capitalize(report.scope()) + " Scope)";

        String meter = efficiencyMeter(report.savingsPct());
        double pct = report.savingsPct();

        return title + "\n" +
               DIVIDER + "\n\n" +
               line("Total commands",  fmt(report.totalCommands())) +
               line("Input tokens",    fmt(report.inputTokens())) +
               line("Output tokens",   fmt(report.outputTokens())) +
               line("Tokens saved",    fmt(report.tokensSaved())
                   + " (" + String.format("%.1f", pct) + "%)") +
               line("Total exec time", report.totalExecMs() + "ms"
                   + " (avg " + report.avgExecMs() + "ms)") +
               "Efficiency meter: " + meter + " "
                   + String.format("%.1f", pct) + "%";
    }

    // ── Efficiency meter ──────────────────────────────────────────────────────

    /**
     * Renders a 24-char wide Unicode block progress meter.
     *
     * <p>Example at 81%: {@code ████████████████████░░░░}
     *
     * @param savingsPct integer percentage 0–100
     * @return 24-character meter string
     */
    public static String efficiencyMeter(int savingsPct) {
        int pct = Math.max(0, Math.min(100, savingsPct));
        // Each character = 100/METER_WIDTH percent
        double charsPerPct = METER_WIDTH / 100.0;
        double filled = pct * charsPerPct;
        int fullBlocks = (int) filled;
        boolean half = (filled - fullBlocks) >= 0.5;
        int emptyBlocks = METER_WIDTH - fullBlocks - (half ? 1 : 0);

        return FULL_BLOCK.repeat(fullBlocks)
            + (half ? HALF_BLOCK : "")
            + EMPTY_BLOCK.repeat(Math.max(0, emptyBlocks));
    }

    // ── 30-day bar chart ──────────────────────────────────────────────────────

    /**
     * Renders a 30-day ASCII bar chart of daily token savings.
     *
     * <pre>
     * Daily Token Savings — Last 30 Days
     * ════════════════════════════════════════════════════════════
     *
     *     Tokens
     *  10k |                    ##
     *   8k |                 ## ## ##
     *   6k |          ##     ## ## ##
     *   4k | ##    ## ## ##  ## ## ##
     *   2k | ## ## ## ## ## ## ## ##
     *       ─────────────────────────────────────────────────────
     *       Jun 1     Jun 8     Jun 15    Jun 22    Jun 28
     * </pre>
     *
     * @param stats list of daily stats (may be sparse — missing days = 0)
     * @param days  total window in days
     */
    public static String renderGraph(List<DailyStat> stats, int days) {
        if (stats.isEmpty()) {
            return "No data for the last " + days + " days.\n" +
                   "Run some commands first: condense git status, condense cargo test, etc.";
        }

        int chartHeight = 8;
        int chartWidth  = Math.min(days, 30);

        // Build day → saved map
        Map<String, Long> byDay = stats.stream().collect(
            Collectors.toMap(DailyStat::day, DailyStat::saved, Long::sum));

        // Generate last `chartWidth` days
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        long[] values = new long[chartWidth];
        String[] labels = new String[chartWidth];
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
        DateTimeFormatter keyFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        for (int i = 0; i < chartWidth; i++) {
            LocalDate day = today.minusDays(chartWidth - 1 - i);
            values[i] = byDay.getOrDefault(day.format(keyFmt), 0L);
            labels[i] = day.format(fmt);
        }

        long maxVal = 0;
        for (long v : values) maxVal = Math.max(maxVal, v);
        if (maxVal == 0) {
            return "No token savings recorded in the last " + days + " days.";
        }

        // Render rows top-to-bottom
        StringBuilder sb = new StringBuilder();
        sb.append("Daily Token Savings — Last ").append(days).append(" Days\n");
        sb.append(DIVIDER).append("\n\n");

        // Bar chart rows
        for (int row = chartHeight; row >= 1; row--) {
            double threshold = (double) row / chartHeight;
            // Y-axis label
            long rowVal = (long)(maxVal * threshold);
            String yLabel = formatShort(rowVal);
            sb.append(String.format("%5s |", yLabel));

            for (long value : values) {
                double barRatio = maxVal == 0 ? 0 : (double) value / maxVal;
                sb.append(barRatio >= threshold ? " ##" : "   ");
            }
            sb.append('\n');
        }

        // X-axis line
        sb.append("       ").append("───".repeat(chartWidth)).append('\n');

        // X-axis labels (every 7 days)
        sb.append("       ");
        for (int i = 0; i < chartWidth; i++) {
            if (i % 7 == 0) {
                String label = labels[i];
                sb.append(label);
                // Pad to fill 7 positions
                int pad = Math.max(0, 21 - label.length());
                sb.append(" ".repeat(pad));
            }
        }
        sb.append('\n');

        return sb.toString().stripTrailing();
    }

    // ── History table ─────────────────────────────────────────────────────────

    /**
     * Renders the {@code --history} table with per-row savings icons.
     *
     * <pre>
     * Recent Commands (last 20)
     * ════════════════════════════════════════════════════════════
     *
     * ▲ git status         raw:  600  →  out:   12   (98%)   42ms
     * ■ npm install        raw:  320  →  out:  120   (63%)  1842ms
     * • echo hello         raw:    3  →  out:    3    (0%)    2ms
     * </pre>
     */
    public static String renderHistory(List<RecentCommand> rows) {
        if (rows.isEmpty()) {
            return "No command history yet. Run some commands to see history.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Recent Commands (last ").append(rows.size()).append(")\n");
        sb.append(DIVIDER).append("\n\n");

        for (RecentCommand row : rows) {
            String icon = savingsIcon(row.savingsPct());
            String cmd = row.command().length() > 22
                ? row.command().substring(0, 21) + "…"
                : row.command();
            sb.append(String.format(
                "%s %-23s raw:%5d  →  out:%5d  (%3d%%)  %dms%n",
                icon, cmd, row.rawTokens(), row.outTokens(),
                row.savingsPct(), row.execMs()));
        }

        return sb.toString().stripTrailing();
    }

    // ── Top commands table ────────────────────────────────────────────────────

    /**
     * Renders the {@code --top N} table.
     *
     * <pre>
     * Top Commands by Tokens Saved
     * ════════════════════════════════════════════════════════════
     *
     *  #   Command              Uses    Saved    Pct
     *  1   git status            127   39,160   81.1%
     *  2   cargo test             12    8,400   72.3%
     * </pre>
     */
    public static String renderTopCommands(List<TopCommand> cmds) {
        if (cmds.isEmpty()) return "No commands recorded yet.";

        StringBuilder sb = new StringBuilder();
        sb.append("Top Commands by Tokens Saved\n");
        sb.append(DIVIDER).append("\n\n");
        sb.append(String.format("  %-3s  %-22s  %-6s  %-9s  %s%n",
            "#", "Command", "Uses", "Saved", "Pct"));
        sb.append("  " + "─".repeat(55) + "\n");

        for (int i = 0; i < cmds.size(); i++) {
            TopCommand c = cmds.get(i);
            sb.append(String.format("  %-3d  %-22s  %-6d  %-9s  %d%%%n",
                i + 1,
                truncate(c.command(), 22),
                c.uses(),
                fmt(c.saved()),
                c.savingsPct()));
        }

        return sb.toString().stripTrailing();
    }

    // ── Daily / Weekly table ──────────────────────────────────────────────────

    public static String renderDailyTable(List<DailyStat> stats) {
        if (stats.isEmpty()) return "No daily data recorded.";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %-12s  %-7s  %-9s  %-9s  %s%n",
            "Date", "Cmds", "Raw", "Out", "Saved"));
        sb.append("  " + "─".repeat(55) + "\n");
        for (DailyStat s : stats) {
            sb.append(String.format("  %-12s  %-7d  %-9s  %-9s  %s (%d%%)%n",
                s.day(), s.count(),
                fmt(s.sumRaw()), fmt(s.sumOut()),
                fmt(s.saved()),
                s.sumRaw() == 0 ? 0 : (int)(100L*(s.sumRaw()-s.sumOut())/s.sumRaw())));
        }
        return sb.toString().stripTrailing();
    }

    public static String renderWeeklyTable(List<WeeklyStat> stats) {
        if (stats.isEmpty()) return "No weekly data recorded.";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %-10s  %-7s  %-9s  %s%n",
            "Week", "Cmds", "Raw", "Saved"));
        sb.append("  " + "─".repeat(42) + "\n");
        for (WeeklyStat s : stats) {
            sb.append(String.format("  %-10s  %-7d  %-9s  %s%n",
                s.week(), s.count(), fmt(s.sumRaw()), fmt(s.saved())));
        }
        return sb.toString().stripTrailing();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String line(String label, String value) {
        return String.format("%-18s %s%n", label + ":", value);
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }

    private static String formatShort(long n) {
        if (n >= 1_000_000) return String.format("%.0fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.0fk", n / 1_000.0);
        return String.valueOf(n);
    }

    private static String savingsIcon(int pct) {
        if (pct >= 80) return "▲";
        if (pct >= 40) return "■";
        return "•";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
