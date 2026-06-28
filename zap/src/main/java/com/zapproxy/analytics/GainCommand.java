package com.zapproxy.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.zapproxy.core.TrackingRepository.*;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

/**
 * {@code zap gain} — displays token savings analytics.
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

    // ── Options ───────────────────────────────────────────────────────────────

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
        defaultValue = "10", paramLabel = "N")
    int top;

    @Option(names = "--since",
        description = "Restrict to last N days. Default: 30.",
        defaultValue = "30", paramLabel = "DAYS")
    int since;

    @Option(names = "--all",
        description = "Include all-time data, ignoring --since.")
    boolean all;

    @Option(names = "--format",
        description = "Output format: 'text' (default) or 'json'.",
        defaultValue = "text", paramLabel = "FORMAT")
    String format;

    // ── Injection ─────────────────────────────────────────────────────────────

    @Inject
    GainRepository gainRepo;

    // ── Jackson for JSON output ───────────────────────────────────────────────

    private static final ObjectMapper JSON = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    // ── Entry point ───────────────────────────────────────────────────────────

    @Override
    public void run() {
        int effectiveSince = all ? 0 : since;
        boolean isJson = "json".equalsIgnoreCase(format);

        try {
            if (isJson) {
                renderJson(effectiveSince);
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

            // --top flag
            if (top != 10) {
                System.out.println(AsciiGraphRenderer.renderTopCommands(
                    gainRepo.topCommands(top, effectiveSince, scope)));
                return;
            }

            // Default: full summary panel
            GainReport report = gainRepo.buildReport(scope, effectiveSince, 5);
            System.out.println(AsciiGraphRenderer.renderSummary(report));

        } catch (Exception e) {
            System.err.println("zap gain: error: " + e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void renderJson(int effectiveSince) throws Exception {
        GainReport report = gainRepo.buildReport(scope, effectiveSince, top);
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
