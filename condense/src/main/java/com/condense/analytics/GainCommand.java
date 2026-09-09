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

    @Option(names = "--model",
        description = "Target LLM model for cost estimation (e.g. 'gpt-4o', 'claude-3-5-sonnet').",
        paramLabel = "MODEL")
    String modelFlag;

    @Option(names = "--list-models",
        description = "List all supported LLM models with pricing and effective dates.")
    boolean listModels;

    @Inject
    GainRepository gainRepo;

    @Inject
    com.condense.core.ConfigLoader configLoader;

    private static final ObjectMapper JSON = com.condense.core.Mappers.JSON;

    @Override
    public void run() {
        if (listModels) {
            renderModelList();
            return;
        }

        int effectiveSince = all ? 0 : since;
        boolean isJson = "json".equalsIgnoreCase(format);
        boolean isCsv = "csv".equalsIgnoreCase(format);
        ModelPricing pricing = resolvePricing();

        try {
            if (isJson) {
                renderJson(effectiveSince, pricing);
                return;
            }

            if (isCsv) {
                renderCsv(effectiveSince, pricing);
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
            GainReport report = gainRepo.buildReport(scope, effectiveSince, 5, pricing);
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

    private ModelPricing resolvePricing() {
        PricingCatalog catalog = PricingCatalog.get();
        if (modelFlag != null && !modelFlag.isBlank()) {
            java.util.Optional<ModelPricing> match = catalog.findModel(modelFlag);
            if (match.isEmpty()) {
                System.err.println("condense gain: warning: unknown model '" + modelFlag
                    + "'. Run with --list-models to see available models. Dollar estimates suppressed.");
                return null;
            }
            return match.get();
        }

        String configuredModel = null;
        if (configLoader != null) {
            try {
                configuredModel = configLoader.load().analytics().model();
            } catch (Exception ignored) {
            }
        }
        if (configuredModel != null && !configuredModel.isBlank()) {
            java.util.Optional<ModelPricing> match = catalog.findModel(configuredModel);
            if (match.isPresent()) {
                return match.get();
            }
        }

        return catalog.defaultModel();
    }

    private void renderModelList() {
        PricingCatalog catalog = PricingCatalog.get();
        System.out.println("Supported LLM Models for Cost Estimation (effective " + catalog.effectiveDate() + "):");
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf(java.util.Locale.ROOT, "%-28s %-12s %-13s %-13s %-12s %s%n",
            "Model", "Provider", "Input / 1M", "Output / 1M", "Effective", "Source");
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────────────────────");
        for (ModelPricing m : catalog.allModels()) {
            System.out.printf(java.util.Locale.ROOT, "%-28s %-12s $%-12.2f $%-12.2f %-12s %s%n",
                m.id(), m.provider(), m.inputCostPerMillion(), m.outputCostPerMillion(), m.effectiveDate(), m.sourceUrl());
        }
        System.out.println("\nDefault model: " + catalog.defaultModel().id());
        System.out.println("Uncertainty note: All dollar calculations inherit ±37% token estimation uncertainty.");
    }

    boolean topRequested() {
        return topFlag != null;
    }

    int topN() {
        return topFlag == null ? 10 : topFlag;
    }

    private void renderJson(int effectiveSince, ModelPricing pricing) throws Exception {
        GainReport report = gainRepo.buildReport(scope, effectiveSince, topN(), pricing);
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private void renderCsv(int effectiveSince, ModelPricing pricing) {
        if (daily) {
            renderDailyCsv(gainRepo.dailyStats(effectiveSince == 0 ? 90 : effectiveSince, scope), pricing);
        } else if (weekly) {
            int weeks = effectiveSince == 0 ? 12 : (effectiveSince / 7 + 1);
            renderWeeklyCsv(gainRepo.weeklyStats(weeks, scope), pricing);
        } else if (historyFlag != null) {
            renderHistoryCsv(gainRepo.recentCommands(historyFlag, scope), pricing);
        } else if (topFlag != null) {
            renderTopCsv(gainRepo.topCommands(topFlag, effectiveSince, scope), pricing);
        } else {
            GainReport report = gainRepo.buildReport(scope, effectiveSince, 5, pricing);
            renderSummaryCsv(report);
        }
    }

    private void renderDailyCsv(List<DailyStat> stats, ModelPricing pricing) {
        System.out.println("date,raw_tokens,filtered_tokens,saved_tokens,est_usd_saved,commands");
        if (stats != null) {
            for (DailyStat s : stats) {
                double savedUsd = pricing != null
                    ? Math.round(Math.max(0L, s.saved()) * (pricing.inputCostPerMillion() / 1_000_000.0) * 1_000_000.0) / 1_000_000.0
                    : 0.0;
                System.out.println(String.format(java.util.Locale.ROOT, "%s,%d,%d,%d,%.6f,%d",
                    escapeCsv(s.day()), s.sumRaw(), s.sumOut(), s.saved(), savedUsd, s.count()));
            }
        }
    }

    private void renderWeeklyCsv(List<WeeklyStat> stats, ModelPricing pricing) {
        System.out.println("week,raw_tokens,filtered_tokens,saved_tokens,est_usd_saved,commands");
        if (stats != null) {
            for (WeeklyStat s : stats) {
                double savedUsd = pricing != null
                    ? Math.round(Math.max(0L, s.saved()) * (pricing.inputCostPerMillion() / 1_000_000.0) * 1_000_000.0) / 1_000_000.0
                    : 0.0;
                System.out.println(String.format(java.util.Locale.ROOT, "%s,%d,%d,%d,%.6f,%d",
                    escapeCsv(s.week()), s.sumRaw(), s.sumOut(), s.saved(), savedUsd, s.count()));
            }
        }
    }

    private void renderTopCsv(List<TopCommand> stats, ModelPricing pricing) {
        System.out.println("command,raw_tokens,filtered_tokens,saved_tokens,est_usd_saved,count");
        if (stats != null) {
            for (TopCommand s : stats) {
                double savedUsd = pricing != null
                    ? Math.round(Math.max(0L, s.saved()) * (pricing.inputCostPerMillion() / 1_000_000.0) * 1_000_000.0) / 1_000_000.0
                    : 0.0;
                System.out.println(String.format(java.util.Locale.ROOT, "%s,%d,%d,%d,%.6f,%d",
                    escapeCsv(s.command()), s.sumRaw(), s.sumOut(), s.saved(), savedUsd, s.uses()));
            }
        }
    }

    private void renderHistoryCsv(List<RecentCommand> commands, ModelPricing pricing) {
        System.out.println("timestamp,command,project,raw_tokens,filtered_tokens,saved_tokens,est_usd_saved,duration_ms");
        if (commands != null) {
            for (RecentCommand c : commands) {
                long saved = Math.max(0L, c.rawTokens() - c.outTokens());
                double savedUsd = pricing != null
                    ? Math.round(saved * (pricing.inputCostPerMillion() / 1_000_000.0) * 1_000_000.0) / 1_000_000.0
                    : 0.0;
                System.out.println(String.format(java.util.Locale.ROOT, "%d,%s,%s,%d,%d,%d,%.6f,%d",
                    c.ts(), escapeCsv(c.command()), escapeCsv(scope != null ? scope : ""),
                    c.rawTokens(), c.outTokens(), saved, savedUsd, c.execMs()));
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
        if (report.cost() != null) {
            CostEstimate c = report.cost();
            System.out.println("cost_model," + escapeCsv(c.model()));
            System.out.println("cost_provider," + escapeCsv(c.provider()));
            System.out.println("cost_currency," + escapeCsv(c.currency()));
            System.out.println(String.format(java.util.Locale.ROOT, "cost_input_rate_per_m,%.2f", c.inputRatePerM()));
            System.out.println(String.format(java.util.Locale.ROOT, "cost_output_rate_per_m,%.2f", c.outputRatePerM()));
            System.out.println(String.format(java.util.Locale.ROOT, "estimated_usd_saved,%.6f", c.estimatedUsdSaved()));
            System.out.println(String.format(java.util.Locale.ROOT, "estimated_raw_usd,%.6f", c.estimatedRawUsd()));
            System.out.println(String.format(java.util.Locale.ROOT, "estimated_filtered_usd,%.6f", c.estimatedFilteredUsd()));
            System.out.println("cost_effective_date," + escapeCsv(c.pricingEffectiveDate()));
            System.out.println("cost_uncertainty," + escapeCsv(c.uncertainty()));
        }
        System.out.println("history_status," + escapeCsv(report.historyStatus()));
    }

    private static String escapeCsv(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n") || val.contains("\r")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}
