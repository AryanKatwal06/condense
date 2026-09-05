package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.filter.strategy.GroupingStrategy;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EsLintTextStage implements FilterStage {
    public static final EsLintTextStage INSTANCE = new EsLintTextStage();
    private static final Pattern RULE_PATTERN =
        Pattern.compile("\\s+\\d+:\\d+\\s+(?:error|warning)\\s+.+?\\s+(\\S+)$");

    private static final Pattern ISSUE_PATTERN =
        Pattern.compile("^\\s*(\\d+):\\d+\\s+(error|warning)\\s+(.+?)\\s+(\\S+)\\s*$");

    private EsLintTextStage() {}

    static List<Document.Finding> findingsFrom(List<String> lines) {
        List<Document.Finding> findings = new ArrayList<>();
        String file = "";
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            Matcher issue = BoundedRegex.matcher(ISSUE_PATTERN, line);
            if (issue.matches()) {
                findings.add(new Document.Finding(
                    file,
                    Integer.parseInt(issue.group(1)),
                    issue.group(4),
                    issue.group(3).strip(),
                    issue.group(2)
                ));
            } else if (!line.contains("  error  ") && !line.contains("  warning  ")
                && !line.startsWith("✖") && !line.startsWith("✓")) {
                file = line.strip();
            }
        }
        return findings;
    }

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
            findingsFrom(lines),
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
