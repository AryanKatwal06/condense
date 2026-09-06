package com.condense.filter.strategy;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.Mappers;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterIncident;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bounded Terraform/OpenTofu machine-UI NDJSON parser. Fail-open to the next
 * stage when the text is not UI JSON or the UI major version is unsupported.
 */
@DeclarativeStage(aliases = {"machine_ui", "machine-ui"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class MachineUiStage implements FilterStage {

    public static final MachineUiStage INSTANCE = new MachineUiStage();

    public static final int MAX_JSON_OBJECTS = 10_000;
    public static final int MAX_RESOURCE_ROWS = 1_000;
    public static final int MAX_DIAGNOSTICS = 200;
    public static final int MAX_LINE_CHARS = 1_048_576;
    public static final String CAPPED_LINE = "condense: machine_ui capped";

    private MachineUiStage() {}

    @Override
    public StageResult process(String input, FilterContext context) {
        String raw = input == null ? "" : input;
        Parsed parsed = parse(raw, context);
        if (parsed == null) {
            return StageResult.continueWith(raw);
        }
        publish(context, parsed.payload);
        return StageResult.stopWith(TextRenderer.renderResource(parsed.payload));
    }

    static Parsed parse(String raw, FilterContext context) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<Document.ResourceRow> rows = new ArrayList<>();
        int jsonObjects = 0;
        int diagnosticCount = 0;
        boolean detected = false;
        boolean capped = false;
        Integer add = null;
        Integer change = null;
        Integer destroy = null;
        Integer replace = null;

        for (String line : raw.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.startsWith("{")) {
                if (looksLikeError(trimmed) && diagnosticCount < MAX_DIAGNOSTICS) {
                    rows.add(Document.ResourceRow.infra(trimmed, "error", null, null));
                    diagnosticCount++;
                }
                continue;
            }
            if (trimmed.length() > MAX_LINE_CHARS) {
                capped = true;
                continue;
            }
            JsonNode node;
            try {
                node = Mappers.JSON.readTree(trimmed);
            } catch (Exception ignored) {
                continue;
            }
            if (node == null || !node.isObject()) {
                continue;
            }
            jsonObjects++;
            if (jsonObjects > MAX_JSON_OBJECTS) {
                capped = true;
                break;
            }
            String type = text(node, "type");
            if (type.isEmpty()) {
                continue;
            }
            if (isUiEvent(node, type)) {
                detected = true;
            }
            if ("version".equals(type)) {
                int major = uiMajor(text(node, "ui"));
                if (major >= 2) {
                    if (context != null) {
                        context.recordIncident(FilterIncident.machineUiVersion("ui " + text(node, "ui")));
                    }
                    return null;
                }
                continue;
            }
            if (!detected && !isUiEvent(node, type)) {
                continue;
            }
            switch (type) {
                case "planned_change", "resource_drift" -> {
                    Document.ResourceRow row = changeRow(node, type);
                    if (row != null && rows.size() < MAX_RESOURCE_ROWS) {
                        rows.add(row);
                        if ("replace".equals(row.action())) {
                            replace = replace == null ? 1 : replace + 1;
                        }
                    } else if (row != null) {
                        capped = true;
                    }
                }
                case "change_summary" -> {
                    JsonNode changes = node.path("changes");
                    if (changes.isObject()) {
                        add = changes.path("add").asInt(0);
                        change = changes.path("change").asInt(0);
                        destroy = changes.path("remove").asInt(0);
                    }
                }
                case "diagnostic" -> {
                    String level = text(node, "@level").toLowerCase(Locale.ROOT);
                    if (!level.isEmpty() && !"error".equals(level) && !"warn".equals(level)
                        && !"warning".equals(level)) {
                        break;
                    }
                    if (diagnosticCount >= MAX_DIAGNOSTICS) {
                        capped = true;
                        break;
                    }
                    Document.ResourceRow row = diagnosticRow(node, level);
                    if (row != null) {
                        rows.add(row);
                        diagnosticCount++;
                    }
                }
                case "outputs" -> {
                    JsonNode outputs = node.path("outputs");
                    if (outputs.isObject()) {
                        outputs.fields().forEachRemaining(entry -> {
                            if (rows.size() >= MAX_RESOURCE_ROWS) {
                                return;
                            }
                            boolean sensitive = entry.getValue().path("sensitive").asBoolean(false);
                            rows.add(Document.ResourceRow.infra(
                                entry.getKey(),
                                "output",
                                sensitive ? "sensitive" : null,
                                null));
                        });
                    }
                }
                case "log" -> {
                    String level = text(node, "@level").toLowerCase(Locale.ROOT);
                    if ("error".equals(level) || "warn".equals(level) || "warning".equals(level)) {
                        if (diagnosticCount >= MAX_DIAGNOSTICS) {
                            capped = true;
                            break;
                        }
                        String message = text(node, "@message");
                        if (!message.isBlank()) {
                            rows.add(Document.ResourceRow.infra(message, level, null, null));
                            diagnosticCount++;
                        }
                    }
                }
                default -> {
                }
            }
        }

        if (!detected) {
            return null;
        }
        if (add == null && rows.isEmpty()) {
            return null;
        }
        Document.ResourceDocument payload = new Document.ResourceDocument(
            rows,
            rows.isEmpty() && (add == null || (add == 0 && (change == null || change == 0)
                && (destroy == null || destroy == 0))),
            Document.ResourceDocument.FORMAT_INFRA,
            add == null ? 0 : add,
            change == null ? 0 : change,
            destroy == null ? 0 : destroy,
            replace,
            capped ? Boolean.TRUE : null);
        return new Parsed(payload);
    }

    private static boolean looksLikeError(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("error") || lower.contains("error:");
    }

    private static boolean isUiEvent(JsonNode node, String type) {
        if ("terraform.ui".equals(text(node, "@module"))) {
            return true;
        }
        return "version".equals(type) && node.has("ui");
    }

    private static int uiMajor(String ui) {
        if (ui == null || ui.isBlank()) {
            return 0;
        }
        int dot = ui.indexOf('.');
        String major = dot < 0 ? ui : ui.substring(0, dot);
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Document.ResourceRow changeRow(JsonNode node, String type) {
        JsonNode change = node.path("change");
        JsonNode resource = change.path("resource");
        String address = text(resource, "addr");
        if (address.isBlank()) {
            address = text(node, "@message");
        }
        if (address.isBlank()) {
            return null;
        }
        String action = text(change, "action");
        if (action.isBlank()) {
            action = "resource_drift".equals(type) ? "drift" : "change";
        }
        return Document.ResourceRow.infra(
            address,
            action,
            blankToNull(text(change, "reason")),
            blankToNull(text(resource, "resource_type")));
    }

    private static Document.ResourceRow diagnosticRow(JsonNode node, String level) {
        JsonNode diagnostic = node.path("diagnostic");
        String address = text(diagnostic, "address");
        if (address.isBlank()) {
            address = text(diagnostic.path("range"), "filename");
        }
        String message = text(diagnostic, "summary");
        if (message.isBlank()) {
            message = text(node, "@message");
        }
        if (address.isBlank() && message.isBlank()) {
            return null;
        }
        String action = level.isBlank() ? "error" : level;
        return Document.ResourceRow.infra(
            address.isBlank() ? message : address,
            action,
            address.isBlank() ? null : blankToNull(message),
            null);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        String text = value.asText("");
        return text == null ? "" : text.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void publish(FilterContext context, Document.ResourceDocument payload) {
        if (context != null && context.documentBuilder() != null) {
            context.documentBuilder().resource(payload);
        }
    }

    record Parsed(Document.ResourceDocument payload) {}
}
