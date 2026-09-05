package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.GroupingStrategy;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class EsLintTextStage implements FilterStage {
    public static final EsLintTextStage INSTANCE = new EsLintTextStage();
    private static final Pattern RULE_PATTERN =
        Pattern.compile("\\s+\\d+:\\d+\\s+(?:error|warning)\\s+.+?\\s+(\\S+)$");

    private EsLintTextStage() {}

    @Override
    public StageResult process(String raw, FilterContext ctx) {
        List<String> lines = raw.lines().toList();
        long errors = lines.stream().filter(l -> l.contains("  error  ")).count();
        long warnings = lines.stream().filter(l -> l.contains("  warning  ")).count();

        boolean clean = errors == 0 && warnings == 0 && (ctx.result() != null && ctx.result().succeeded());
        Map<String, Integer> grouped = GroupingStrategy.group(lines, RULE_PATTERN, false);
        List<Document.GroupCount> groups = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            groups.add(new Document.GroupCount(entry.getKey(), entry.getValue()));
        }
        Document.DiagnosticDocument payload = new Document.DiagnosticDocument(
            List.of(),
            (int) errors,
            (int) warnings,
            groups,
            Document.DiagnosticDocument.GROUP_ALIGNED,
            clean
        );
        if (ctx != null && ctx.documentBuilder() != null) {
            ctx.documentBuilder().diagnostic(payload);
        }
        return clean
            ? StageResult.stopWith(TextRenderer.renderDiagnostic(payload))
            : StageResult.continueWith(TextRenderer.renderDiagnostic(payload));
    }
}
