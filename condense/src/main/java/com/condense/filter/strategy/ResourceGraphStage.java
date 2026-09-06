package com.condense.filter.strategy;

import com.condense.annotation.DeclarativeStage;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideConfig;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded multi-record compressor for Terraform-style resource addresses.
 * Human plans and fmt file lists fall through unless most lines look like addresses.
 */
@DeclarativeStage(aliases = {"resource_graph", "resource-graph"}, capability = "RESHAPE", factory = "fromDef")
public final class ResourceGraphStage implements FilterStage {

    public static final String KEY_RESOURCE_TYPE = "resource_type";
    public static final String KEY_WHOLE_LINE = "whole_line";
    public static final int DEFAULT_TOP_N = 20;
    public static final int DEFAULT_MAX_LINES = 2_000;
    public static final double ADDRESS_RATIO = 0.80;

    private static final Pattern ADDRESS = Pattern.compile(
        "^(?:module\\.[A-Za-z0-9_-]+(?:\\[[^\\]]+\\])?\\.)*"
            + "(?:data\\.)?"
            + "(?:[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+|[A-Za-z][A-Za-z0-9_]*)"
            + "\\.[A-Za-z0-9_-]+"
            + "(?:\\[[^\\]]+\\])?$");

    private final Function<String, String> keyOf;
    private final String headerTemplate;
    private final int topN;
    private final int maxLines;
    private final String fallback;

    public ResourceGraphStage(
            Function<String, String> keyOf,
            String headerTemplate,
            int topN,
            int maxLines,
            String fallback
    ) {
        this.keyOf = keyOf;
        this.headerTemplate = headerTemplate == null ? "" : headerTemplate;
        this.topN = topN;
        this.maxLines = maxLines;
        this.fallback = fallback == null ? "" : fallback;
    }

    public static FilterStage fromDef(FilterOverrideConfig.StageDef stageDef) {
        String key = stageDef != null && stageDef.key() != null ? stageDef.key() : KEY_RESOURCE_TYPE;
        String header = stageDef != null && stageDef.header() != null ? stageDef.header() : "";
        int topN = stageDef != null && stageDef.topN() != null && stageDef.topN() > 0
            ? stageDef.topN() : DEFAULT_TOP_N;
        int maxLines = stageDef != null && stageDef.maxLines() != null && stageDef.maxLines() > 0
            ? stageDef.maxLines() : DEFAULT_MAX_LINES;
        String fallback = stageDef != null && stageDef.fallback() != null ? stageDef.fallback() : "";
        return ofPreset(key, header, topN, maxLines, fallback);
    }

    public static ResourceGraphStage ofPreset(
            String key,
            String headerTemplate,
            int topN,
            int maxLines,
            String fallback
    ) {
        Function<String, String> keyOf = switch (normalize(key)) {
            case KEY_WHOLE_LINE -> line -> line;
            default -> ResourceGraphStage::resourceTypeOf;
        };
        return new ResourceGraphStage(keyOf, headerTemplate, topN, maxLines, fallback);
    }

    public static void validate(String location, FilterOverrideConfig.StageDef stage, List<String> errors) {
        String key = stage.key() == null ? KEY_RESOURCE_TYPE : normalize(stage.key());
        if (!KEY_RESOURCE_TYPE.equals(key) && !KEY_WHOLE_LINE.equals(key)) {
            errors.add(location + ": 'key' must be '" + KEY_RESOURCE_TYPE
                + "' or '" + KEY_WHOLE_LINE + "'");
        }
        if (stage.topN() != null && (stage.topN() < 1 || stage.topN() > 10_000)) {
            errors.add(location + ": 'top_n' must be between 1 and 10000, got: " + stage.topN());
        }
        if (stage.maxLines() != null && (stage.maxLines() < 1 || stage.maxLines() > 100_000)) {
            errors.add(location + ": 'max_lines' must be between 1 and 100000, got: " + stage.maxLines());
        }
    }

    @Override
    public StageResult process(String input, FilterContext context) {
        String raw = input == null ? "" : input;
        List<String> lines = raw.lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .toList();
        if (lines.isEmpty()) {
            if (fallback.isBlank()) {
                return StageResult.continueWith(raw);
            }
            return StageResult.stopWith(fallback);
        }
        if (lines.size() < 2) {
            return StageResult.continueWith(raw);
        }
        long addresses = lines.stream().filter(ResourceGraphStage::isAddress).count();
        if ((double) addresses / lines.size() < ADDRESS_RATIO) {
            return StageResult.continueWith(raw);
        }

        Map<String, Integer> groups = new LinkedHashMap<>();
        List<Document.ResourceRow> rows = new ArrayList<>();
        boolean capped = false;
        if (!headerTemplate.isBlank()) {
            rows.add(new Document.ResourceRow("", "", "", "", headerTemplate
                .replace("{lines}", Integer.toString(lines.size()))
                .replace("{keys}", "0"), null, null, null, null));
        }
        int emitted = 0;
        for (String line : lines) {
            if (!isAddress(line)) {
                continue;
            }
            groups.merge(keyOf.apply(line), 1, Integer::sum);
            if (emitted < maxLines) {
                rows.add(Document.ResourceRow.infra(line, null, null, resourceTypeOf(line)));
                emitted++;
            } else {
                capped = true;
            }
        }
        if (!headerTemplate.isBlank() && !rows.isEmpty()) {
            rows.set(0, new Document.ResourceRow(
                "", "", "", "",
                headerTemplate
                    .replace("{lines}", Integer.toString(lines.size()))
                    .replace("{keys}", Integer.toString(groups.size())),
                null, null, null, null));
        }
        List<Document.ResourceRow> withGroups = new ArrayList<>();
        if (!rows.isEmpty() && !headerTemplate.isBlank()) {
            withGroups.add(rows.getFirst());
        }
        groups.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(topN)
            .forEach(entry -> withGroups.add(new Document.ResourceRow(
                "", "", "", "",
                entry.getKey() + ": " + entry.getValue(),
                null, null, null, null)));
        int start = (!rows.isEmpty() && !headerTemplate.isBlank()) ? 1 : 0;
        for (int i = start; i < rows.size(); i++) {
            withGroups.add(rows.get(i));
        }
        Document.ResourceDocument payload = new Document.ResourceDocument(
            withGroups,
            withGroups.isEmpty(),
            Document.ResourceDocument.FORMAT_INFRA,
            null,
            null,
            null,
            null,
            capped ? Boolean.TRUE : null);
        if (context != null && context.documentBuilder() != null) {
            context.documentBuilder().resource(payload);
        }
        return StageResult.stopWith(TextRenderer.renderResource(payload));
    }

    static boolean isAddress(String line) {
        if (line == null || line.isBlank() || line.contains(" ") || line.contains("/")) {
            return false;
        }
        if (!ADDRESS.matcher(line).matches()) {
            return false;
        }
        if (line.startsWith("module.") || line.startsWith("data.")) {
            return true;
        }
        int dot = line.indexOf('.');
        return dot > 0 && line.substring(0, dot).contains("_");
    }

    static String resourceTypeOf(String address) {
        if (address == null) {
            return "";
        }
        String rest = address;
        Matcher module = Pattern.compile("^module\\.[A-Za-z0-9_-]+(?:\\[[^\\]]+\\])?\\.").matcher(rest);
        while (module.find()) {
            rest = rest.substring(module.end());
            module = Pattern.compile("^module\\.[A-Za-z0-9_-]+(?:\\[[^\\]]+\\])?\\.").matcher(rest);
        }
        if (rest.startsWith("data.")) {
            rest = rest.substring("data.".length());
        }
        int dot = rest.indexOf('.');
        return dot > 0 ? rest.substring(0, dot) : rest;
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }
}
