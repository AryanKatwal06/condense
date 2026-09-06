package com.condense.filter.strategy;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.Mappers;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-document {@code terraform validate -json} / {@code tofu validate -json}.
 * Arrays, NDJSON, and human text fall through.
 */
@DeclarativeStage(aliases = {"validate_json", "validate-json"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class ValidateJsonStage implements FilterStage {

    public static final ValidateJsonStage INSTANCE = new ValidateJsonStage();
    public static final int MAX_CHARS = 1_048_576;
    public static final int MAX_DEPTH = 8;
    public static final String TOOL = "validate";

    private ValidateJsonStage() {}

    @Override
    public StageResult process(String input, FilterContext context) {
        String raw = input == null ? "" : input;
        Document.DiagnosticDocument payload = tryParse(raw);
        if (payload == null) {
            return StageResult.continueWith(raw);
        }
        if (context != null && context.documentBuilder() != null) {
            context.documentBuilder().diagnostic(payload);
        }
        return StageResult.stopWith(TextRenderer.renderDiagnostic(payload));
    }

    static Document.DiagnosticDocument tryParse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{") || trimmed.length() > MAX_CHARS) {
            return null;
        }
        long topLevelObjects = trimmed.lines().filter(line -> line.startsWith("{")).count();
        if (topLevelObjects > 1) {
            return null;
        }
        try {
            JsonNode root = Mappers.JSON.readTree(trimmed);
            if (root == null || !root.isObject() || depth(root, 0) > MAX_DEPTH) {
                return null;
            }
            boolean hasValid = root.has("valid") && root.get("valid").isBoolean();
            boolean hasDiagnostics = root.has("diagnostics") && root.get("diagnostics").isArray();
            if (!hasValid && !hasDiagnostics) {
                return null;
            }
            List<Document.Finding> findings = new ArrayList<>();
            int errors = root.path("error_count").asInt(-1);
            int warnings = root.path("warning_count").asInt(-1);
            JsonNode diagnostics = root.path("diagnostics");
            if (diagnostics.isArray()) {
                for (JsonNode item : diagnostics) {
                    String severity = text(item, "severity");
                    if (severity.isBlank()) {
                        severity = "error";
                    }
                    String file = text(item.path("range"), "filename");
                    if (file.isBlank()) {
                        file = text(item, "address");
                    }
                    Integer line = null;
                    JsonNode start = item.path("range").path("start");
                    if (start.has("line") && start.get("line").isNumber()) {
                        line = start.get("line").asInt();
                    }
                    String message = text(item, "summary");
                    if (message.isBlank()) {
                        message = text(item, "detail");
                    }
                    findings.add(new Document.Finding(file, line, "", message, severity));
                }
            }
            if (errors < 0 || warnings < 0) {
                errors = 0;
                warnings = 0;
                for (Document.Finding finding : findings) {
                    if ("warning".equalsIgnoreCase(finding.severity())
                        || "warn".equalsIgnoreCase(finding.severity())) {
                        warnings++;
                    } else {
                        errors++;
                    }
                }
            }
            boolean valid = root.path("valid").asBoolean(errors == 0);
            boolean clean = valid && errors == 0 && warnings == 0 && findings.isEmpty();
            return new Document.DiagnosticDocument(
                findings,
                errors,
                warnings,
                List.of(),
                Document.DiagnosticDocument.GROUP_COLON,
                clean,
                TOOL);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int depth(JsonNode node, int current) {
        if (node == null || node.isValueNode() || node.isNull()) {
            return current;
        }
        int max = current;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                max = Math.max(max, depth(fields.next().getValue(), current + 1));
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                max = Math.max(max, depth(child, current + 1));
            }
        }
        return max;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        String value = node.get(field).asText("");
        return value == null ? "" : value.trim();
    }
}
