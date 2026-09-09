package com.condense.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.condense.core.TrackingRepository.*;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

/**
 * {@code condense gain} — displays token savings analytics.
 *
 * <p>Default output (no flags): summary panel with efficiency meter.<br>
 * With {@code --graph}: 30-day ASCII bar chart.<br>
 * With {@code --history}: recent command table.<br>
 * With {@code --top N}: top-N commands by tokens saved.<br>
 * With {@code --format json}: machine-readable JSON.
 *
 * <p>Scope:
 * <ul>
 *   <li>{@code --scope global} (default): all projects</li>
 *   <li>{@code --scope project}: current working directory only</li>
 * </ul>
 */
@Command(
    name = "gain",
    description = "Show token savings statistics.",
    mixinStandardHelpOptions = true
)
public class GainCommand implements Runnable {



    @Option(names = "--graph",
        description = "Render a 30-day ASCII bar chart of daily token savings.")
    boolean graph;

    @Option(names = "--history", description = "Show last N recent commands.",
        arity = "0..1", fallbackValue = "20", paramLabel = "N")
    Integer historyFlag;   // null if not passed, non-null if --history [N] was used

    @Option(names = "--scope",
        description = "Scope: 'global' (default) or 'project' (current directory).",
        defaultValue = "global", paramLabel = "SCOPE")
    String scope;

    @Option(names = "--daily",
        description = "Show per-day breakdown table.")
    boolean daily;

    @Option(names = "--weekly",
        description = "Show per-week breakdown table.")
    boolean weekly;

    @Option(names = "--top",
        description = "Show top N commands by tokens saved. Default: 10.",
        arity = "0..1", fallbackValue = "10", paramLabel = "N")
    Integer topFlag;

    @Option(names = "--since",
        description = "Restrict to last N days. Default: 30.",
        defaultValue = "30", paramLabel = "DAYS")
    int since;

    @Option(names = "--all",
        description = "Include all-time data, ignoring --since.")
    boolean all;

    @Option(names = "--format",
        description = "Output format: 'text' (default), 'json', or 'csv'.",
        defaultValue = "text", paramLabel = "FORMAT")
    String format;

    @Inject
    GainRepository gainRepo;

    private static final ObjectMapper JSON = com.condense.core.Mappers.JSON;

    @Override
    public void run() {
        int effectiveSince = all ? 0 : since;
        boolean isJson = "json".equalsIgnoreCase(format);
        boolean isCsv = "csv".equalsIgnoreCase(format);

        try {
            if (isJson) {
                renderJson(effectiveSince);
                return;
            }

            if (isCsv) {
                renderCsv(effectiveSince);
                return;
            }

            if (graph) {
                List<DailyStat> stats = gainRepo.dailyStats(effectiveSince == 0 ? 30 : effectiveSince, scope);
                System.out.println(AsciiGraphRenderer.renderGraph(stats, effectiveSince == 0 ? 30 : effectiveSince));
                return;
            }

            if (daily) {
                System.out.println(AsciiGraphRenderer.renderDailyTable(
                    gainRepo.dailyStats(effectiveSince == 0 ? 90 : effectiveSince, scope)));
                return;
            }

            if (weekly) {
                int weeks = effectiveSince == 0 ? 12 : (effectiveSince / 7 + 1);
                System.out.println(AsciiGraphRenderer.renderWeeklyTable(
                    gainRepo.weeklyStats(weeks, scope)));
                return;
            }

            if (historyFlag != null) {
                System.out.println(AsciiGraphRenderer.renderHistory(
                    gainRepo.recentCommands(historyFlag, scope)));
                return;
            }

            if (topFlag != null) {
                System.out.println(AsciiGraphRenderer.renderTopCommands(
                    gainRepo.topCommands(topFlag, effectiveSince, scope)));
                return;
            }

            // Default: full summary panel
            GainReport report = gainRepo.buildReport(scope, effectiveSince, 5);
            System.out.println(AsciiGraphRenderer.renderSummary(report));

        } catch (Exception e) {
            System.err.println("condense gain: error: " + e.getMessage());
        } finally {
            if (gainRepo.isDegraded()) {
                System.err.println("⚠ analytics unavailable — persistence failed, see logs");
            } else if (gainRepo.hasNoCommands()) {
                System.err.println("No tracking data yet. Run condense doctor to see why.");
            }
            gainRepo.close();
        }
    }

    boolean topRequested() {
        return topFlag != null;
    }

    int topN() {
        return topFlag == null ? 10 : topFlag;
    }

    private void renderJson(int effectiveSince) throws Exception {
        GainReport report = gainRepo.buildReport(scope, effectiveSince, topN());
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private void renderCsv(int effectiveSince) {
        if (daily) {
            renderDailyCsv(gainRepo.dailyStats(effectiveSince == 0 ? 90 : effectiveSince, scope));
        } else if (weekly) {
            int weeks = effectiveSince == 0 ? 12 : (effectiveSince / 7 + 1);
            renderWeeklyCsv(gainRepo.weeklyStats(weeks, scope));
        } else if (historyFlag != null) {
            renderHistoryCsv(gainRepo.recentCommands(historyFlag, scope));
        } else if (topFlag != null) {
            renderTopCsv(gainRepo.topCommands(topFlag, effectiveSince, scope));
        } else {
            GainReport report = gainRepo.buildReport(scope, effectiveSince, 5);
            renderSummaryCsv(report);
        }
    }

    private void renderDailyCsv(List<DailyStat> stats) {
        System.out.println("date,raw_tokens,filtered_tokens,saved_tokens,commands");
        if (stats != null) {
            for (DailyStat s : stats) {
                System.out.println(String.format(java.util.Locale.ROOT, "%s,%d,%d,%d,%d",
                    escapeCsv(s.day()), s.sumRaw(), s.sumOut(), s.saved(), s.count()));
            }
        }
    }

    private void renderWeeklyCsv(List<WeeklyStat> stats) {
        System.out.println("week,raw_tokens,filtered_tokens,saved_tokens,commands");
        if (stats != null) {
            for (WeeklyStat s : stats) {
                System.out.println(String.format(java.util.Locale.ROOT, "%s,%d,%d,%d,%d",
                    escapeCsv(s.week()), s.sumRaw(), s.sumOut(), s.saved(), s.count()));
            }
        }
    }

    private void renderTopCsv(List<TopCommand> stats) {
        System.out.println("command,raw_tokens,filtered_tokens,saved_tokens,count");
        if (stats != null) {
            for (TopCommand s : stats) {
                System.out.println(String.format(java.util.Locale.ROOT, "%s,%d,%d,%d,%d",
                    escapeCsv(s.command()), s.sumRaw(), s.sumOut(), s.saved(), s.uses()));
            }
        }
    }

    private void renderHistoryCsv(List<RecentCommand> commands) {
        System.out.println("timestamp,command,project,raw_tokens,filtered_tokens,saved_tokens,duration_ms");
        if (commands != null) {
            for (RecentCommand c : commands) {
                System.out.println(String.format(java.util.Locale.ROOT, "%d,%s,%s,%d,%d,%d,%d",
                    c.ts(), escapeCsv(c.command()), escapeCsv(scope != null ? scope : ""),
                    c.rawTokens(), c.outTokens(), (c.rawTokens() - c.outTokens()), c.execMs()));
            }
        }
    }

    private void renderSummaryCsv(GainReport report) {
        System.out.println("metric,value");
        System.out.println("total_commands," + report.totalCommands());
        System.out.println("input_tokens," + report.inputTokens());
        System.out.println("output_tokens," + report.outputTokens());
        System.out.println("tokens_saved," + report.tokensSaved());
        System.out.println("savings_pct," + report.savingsPct());
        System.out.println("total_exec_time_ms," + report.totalExecMs());
        System.out.println("avg_exec_time_ms," + report.avgExecMs());
    }

    private static String escapeCsv(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n") || val.contains("\r")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}
