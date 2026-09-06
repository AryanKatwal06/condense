package com.condense.filter.strategy;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.Mappers;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code dotnet format --report} JSON array. Objects and human text fall through.
 */
@DeclarativeStage(aliases = {"format_report", "format-report"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class FormatReportStage implements FilterStage {

    public static final FormatReportStage INSTANCE = new FormatReportStage();
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_FINDINGS = 200;
    public static final String CAPPED_LINE = "condense: format_report capped";
    public static final String TOOL = "dotnet-format";

    private FormatReportStage() {}

    @Override
    public StageResult process(String input, FilterContext context) {
        String raw = input == null ? "" : input;
        List<Path> files = ArtifactFiles.find(context, "format-report.json", ".json");
        Parsed parsed = null;
        for (Path file : files) {
            parsed = tryFile(file);
            if (parsed != null) {
                break;
            }
        }
        if (parsed == null) {
            parsed = tryParse(raw);
        }
        if (parsed == null) {
            return StageResult.continueWith(raw);
        }
        if (context != null && context.documentBuilder() != null) {
            context.documentBuilder().diagnostic(parsed.payload);
        }
        String text = TextRenderer.renderDiagnostic(parsed.payload);
        if (parsed.capped) {
            text = text.isBlank() ? CAPPED_LINE : text + "\n" + CAPPED_LINE;
        }
        return StageResult.stopWith(text);
    }

    static Parsed tryFile(Path file) {
        try {
            if (Files.size(file) > MAX_BYTES) {
                return null;
            }
            return tryParse(Files.readString(file));
        } catch (Exception ignored) {
            return null;
        }
    }

    static Parsed tryParse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("[") || trimmed.length() > MAX_BYTES) {
            return null;
        }
        try {
            JsonNode root = Mappers.JSON.readTree(trimmed);
            if (root == null || !root.isArray() || depth(root, 0) > MAX_DEPTH) {
                return null;
            }
            List<Document.Finding> findings = new ArrayList<>();
            boolean detected = root.isEmpty();
            boolean capped = false;
            for (JsonNode document : root) {
                if (document == null || !document.isObject()) {
                    continue;
                }
                if (document.has("FilePath") || document.has("FileName") || document.has("FileChanges")) {
                    detected = true;
                }
                String file = text(document, "FilePath");
                if (file.isBlank()) {
                    file = text(document, "FileName");
                }
                JsonNode changes = document.path("FileChanges");
                if (!changes.isArray()) {
                    continue;
                }
                for (JsonNode change : changes) {
                    if (findings.size() >= MAX_FINDINGS) {
                        capped = true;
                        break;
                    }
                    String message = text(change, "FormatDescription");
                    String code = text(change, "DiagnosticId");
                    if (message.isBlank() && code.isBlank()) {
                        continue;
                    }
                    Integer line = null;
                    if (change.has("LineNumber") && change.get("LineNumber").isNumber()) {
                        line = change.get("LineNumber").asInt();
                    }
                    String display = code.isBlank() ? message : code + ": " + message;
                    findings.add(new Document.Finding(file, line, code, display, "warning"));
                }
            }
            if (!detected) {
                return null;
            }
            boolean clean = findings.isEmpty() && !capped;
            Document.DiagnosticDocument payload = new Document.DiagnosticDocument(
                findings,
                0,
                findings.size(),
                List.of(),
                Document.DiagnosticDocument.GROUP_COLON,
                clean,
                TOOL);
            return new Parsed(payload, capped);
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

    static final class Parsed {
        final Document.DiagnosticDocument payload;
        final boolean capped;

        Parsed(Document.DiagnosticDocument payload, boolean capped) {
            this.payload = payload;
            this.capped = capped;
        }
    }
}
