package com.condense.filter.stage;

import com.condense.core.Mappers;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EsLintJsonStage implements FilterStage {
    public static final EsLintJsonStage INSTANCE = new EsLintJsonStage();

    private EsLintJsonStage() {}

    @Override
    public StageResult process(String raw, FilterContext ctx) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            Document.DiagnosticDocument payload = tryParseJson(trimmed);
            if (payload != null) {
                if (ctx != null && ctx.documentBuilder() != null) {
                    ctx.documentBuilder().diagnostic(payload);
                }
                return StageResult.stopWith(TextRenderer.renderDiagnostic(payload));
            }
        }
        return StageResult.continueWith(raw);
    }

    private static Document.DiagnosticDocument tryParseJson(String json) {
        try {
            var root = Mappers.JSON.readTree(json);
            if (!root.isArray()) {
                return null;
            }

            long errors = 0;
            long warnings = 0;
            var grouped = new LinkedHashMap<String, Integer>();
            List<Document.Finding> findings = new ArrayList<>();

            for (var file : root) {
                errors += file.path("errorCount").asLong(0);
                warnings += file.path("warningCount").asLong(0);
                String path = file.path("filePath").asText("");
                for (var msg : file.path("messages")) {
                    String ruleId = msg.path("ruleId").asText("unknown");
                    grouped.merge(ruleId, 1, Integer::sum);
                    int severityCode = msg.path("severity").asInt(1);
                    String severity = severityCode >= 2 ? "error" : "warning";
                    Integer line = msg.has("line") && !msg.get("line").isNull()
                        ? msg.path("line").asInt()
                        : null;
                    findings.add(new Document.Finding(
                        path,
                        line,
                        ruleId,
                        msg.path("message").asText(""),
                        severity
                    ));
                }
            }

            List<Document.GroupCount> groups = grouped.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new Document.GroupCount(e.getKey(), e.getValue()))
                .toList();

            boolean clean = errors == 0 && warnings == 0;
            return new Document.DiagnosticDocument(
                findings,
                (int) errors,
                (int) warnings,
                groups,
                Document.DiagnosticDocument.GROUP_COLON,
                clean
            );
        } catch (Exception e) {
            return null;
        }
    }
}
