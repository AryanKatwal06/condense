package com.condense.propose;

import com.condense.filter.pipeline.config.DefinitionMappers;
import com.condense.filter.pipeline.config.FilterOverrideConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic schema-v1 override TOML. Jackson's TOML writer is not the source of truth.
 */
public final class ProposeToml {

    private ProposeToml() {}

    public static FilterOverrideConfig.StageDef strategy(String name) {
        return new FilterOverrideConfig.StageDef(
            name, null, null, null, null, List.of(), Map.of(),
            null, null, null, null, null, null, null, null, null, null);
    }

    public static FilterOverrideConfig.StageDef tailLines(int maxLines, boolean skipBlank) {
        return new FilterOverrideConfig.StageDef(
            "tail_lines", null, null, null, null, List.of(), Map.of(),
            maxLines, skipBlank, null, null, null, null, null, null, null, null);
    }

    public static List<FilterOverrideConfig.StageDef> unmatchedStages(int tailLines) {
        return List.of(strategy("ansi_strip"), tailLines(tailLines, true));
    }

    public static String fragment(String command, List<FilterOverrideConfig.StageDef> stages) {
        StringBuilder sb = new StringBuilder();
        appendFilter(sb, command, stages);
        return sb.toString();
    }

    public static String document(Map<String, List<FilterOverrideConfig.StageDef>> filters) {
        StringBuilder sb = new StringBuilder();
        sb.append("schema_version = 1\n");
        if (filters == null || filters.isEmpty()) {
            return sb.toString();
        }
        for (Map.Entry<String, List<FilterOverrideConfig.StageDef>> entry : filters.entrySet()) {
            sb.append('\n');
            appendFilter(sb, entry.getKey(), entry.getValue());
        }
        return sb.toString();
    }

    public static FilterOverrideConfig.FileConfig parse(String toml) throws Exception {
        return DefinitionMappers.STRICT_TOML.readValue(toml, FilterOverrideConfig.FileConfig.class);
    }

    static void appendFilter(StringBuilder sb, String command, List<FilterOverrideConfig.StageDef> stages) {
        sb.append("[filters.").append(quotedKey(command)).append("]\n");
        sb.append("stages = ");
        if (stages == null || stages.isEmpty()) {
            sb.append("[]\n");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < stages.size(); i++) {
            sb.append("  ").append(stageInline(stages.get(i)));
            if (i + 1 < stages.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("]\n");
    }

    static String quotedKey(String command) {
        return '"' + escape(command == null ? "" : command.trim().toLowerCase(Locale.ROOT)) + '"';
    }

    static String stageInline(FilterOverrideConfig.StageDef stage) {
        List<String> fields = new ArrayList<>();
        fields.add("strategy = " + quoted(stage.strategy()));
        addInt(fields, "window_size", stage.windowSize());
        addString(fields, "pattern", stage.pattern());
        addBool(fields, "include_other", stage.includeOther());
        addString(fields, "initial_state", stage.initialState());
        if (stage.transitions() != null && !stage.transitions().isEmpty()) {
            fields.add("transitions = " + transitionsArray(stage.transitions()));
        }
        if (stage.defaultActions() != null && !stage.defaultActions().isEmpty()) {
            fields.add("default_actions = " + mapInline(stage.defaultActions()));
        }
        addInt(fields, "max_lines", stage.maxLines());
        addBool(fields, "skip_blank", stage.skipBlank());
        addBool(fields, "header_only_when_truncating", stage.headerOnlyWhenTruncating());
        addInt(fields, "head", stage.head());
        addInt(fields, "tail", stage.tail());
        addString(fields, "key", stage.key());
        addString(fields, "header", stage.header());
        addInt(fields, "top_n", stage.topN());
        addString(fields, "format", stage.format());
        addString(fields, "fallback", stage.fallback());
        return "{ " + String.join(", ", fields) + " }";
    }

    private static String transitionsArray(List<FilterOverrideConfig.TransitionDef> transitions) {
        List<String> items = new ArrayList<>();
        for (FilterOverrideConfig.TransitionDef t : transitions) {
            List<String> fields = new ArrayList<>();
            addString(fields, "from_state", t.fromState());
            addString(fields, "pattern", t.pattern());
            addString(fields, "action", t.action());
            addString(fields, "next_state", t.nextState());
            items.add("{ " + String.join(", ", fields) + " }");
        }
        return "[" + String.join(", ", items) + "]";
    }

    private static String mapInline(Map<String, String> map) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>(map);
        List<String> fields = new ArrayList<>();
        for (Map.Entry<String, String> e : copy.entrySet()) {
            fields.add(bareOrQuoted(e.getKey()) + " = " + quoted(e.getValue()));
        }
        return "{ " + String.join(", ", fields) + " }";
    }

    private static void addInt(List<String> fields, String key, Integer value) {
        if (value != null) {
            fields.add(key + " = " + value);
        }
    }

    private static void addBool(List<String> fields, String key, Boolean value) {
        if (value != null) {
            fields.add(key + " = " + value);
        }
    }

    private static void addString(List<String> fields, String key, String value) {
        if (value != null) {
            fields.add(key + " = " + quoted(value));
        }
    }

    private static String quoted(String value) {
        return '"' + escape(value == null ? "" : value) + '"';
    }

    private static String bareOrQuoted(String key) {
        if (key != null && key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return key;
        }
        return quoted(key);
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\f", "\\f");
    }
}
