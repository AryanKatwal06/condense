package com.condense.explain;

import com.condense.analytics.EstimatorInfo;

import java.util.List;
import java.util.Locale;

/**
 * Human-readable rendering of {@link ExplainReport}.
 */
public final class ExplainTextRenderer {

    private ExplainTextRenderer() {}

    public static String render(ExplainReport report) {
        StringBuilder out = new StringBuilder();
        out.append("Condense explain\n");
        out.append("───────────────\n");
        out.append("Command:   ").append(report.command()).append('\n');
        out.append("Filter:    ").append(report.filter()).append('\n');
        out.append("Pipeline:  ").append(report.tier());
        if (report.source() != null && !report.source().isBlank()) {
            out.append("  ").append(report.source());
        }
        out.append('\n');
        if (report.skippedTiers() != null && !report.skippedTiers().isEmpty()) {
            out.append("Skipped:   ");
            boolean first = true;
            for (ExplainReport.SkippedTier skipped : report.skippedTiers()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                out.append(skipped.tier()).append(" (").append(skipped.reason()).append(')');
            }
            out.append('\n');
        }
        if (report.gate() != null && report.gate().fired()) {
            out.append("Gate:      ").append(report.gate().kind());
            if (report.gate().detail() != null) {
                out.append("  ").append(report.gate().detail());
            }
            out.append('\n');
        }
        out.append('\n');
        out.append(String.format(Locale.ROOT, "Lines   %d → %d   (net %+d)%n",
            report.inputLines(), report.outputLines(), -report.lineDelta()));
        out.append(String.format(Locale.ROOT, "Tokens  %d → %d   (net %+d)  estimates, %s%n",
            report.inputTokens(), report.outputTokens(), -report.tokenDelta(),
            formatEstimator(report.estimator())));
        if (report.stages() != null && !report.stages().isEmpty()) {
            out.append('\n');
            out.append(String.format(Locale.ROOT, "  %-4s %-22s %10s %10s %s%n",
                "#", "stage", "in → out", "drop/add", "tokens"));
            int index = 1;
            for (ExplainReport.Stage stage : report.stages()) {
                out.append(String.format(Locale.ROOT, "  %-4d %-22s %4d → %-4d %4d/%-4d %d → %d%n",
                    index++,
                    stage.id(),
                    stage.inputLines(),
                    stage.outputLines(),
                    stage.droppedLines(),
                    stage.addedLines(),
                    stage.inputTokens(),
                    stage.outputTokens()));
            }
            for (ExplainReport.Stage stage : report.stages()) {
                appendSamples(out, stage);
            }
        }
        return out.toString();
    }

    private static void appendSamples(StringBuilder out, ExplainReport.Stage stage) {
        List<String> dropped = stage.droppedSample();
        if (dropped == null || dropped.isEmpty()) {
            return;
        }
        out.append('\n');
        out.append("Dropped by ").append(stage.id()).append(" (")
            .append(stage.droppedLines());
        if (stage.droppedTruncated()) {
            out.append(", showing ").append(dropped.size());
        }
        out.append("):\n");
        for (String line : dropped) {
            out.append("  - ").append(line).append('\n');
        }
    }

    private static String formatEstimator(EstimatorInfo info) {
        EstimatorInfo estimator = info == null ? EstimatorInfo.current() : info;
        int pct = (int) Math.round(estimator.p95RelError() * 100);
        return estimator.name() + ", p95 " + pct + "% vs " + estimator.reference();
    }
}
