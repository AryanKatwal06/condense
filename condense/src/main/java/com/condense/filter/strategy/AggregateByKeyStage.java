package com.condense.filter.strategy;

import com.condense.annotation.DeclarativeStage;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Counts non-blank lines by a derived key and formats the top keys.
 */
@DeclarativeStage(aliases = {"aggregate_by_key", "aggregate-by-key"}, capability = "RESHAPE", factory = "fromDef")
public final class AggregateByKeyStage implements FilterStage {

    public interface HeaderFormatter {
        String format(int lineCount, int keyCount);
    }

    private final Function<String, String> keyOf;
    private final HeaderFormatter header;
    private final int topN;

    public static final String KEY_PREFIX_BEFORE_COLON = "prefix_before_colon";
    public static final String KEY_FILE_EXTENSION = "file_extension";

    public AggregateByKeyStage(Function<String, String> keyOf, HeaderFormatter header, int topN) {
        this.keyOf = keyOf;
        this.header = header;
        this.topN = topN;
    }

    /**
     * Declarative constructor. {@code key} is a closed preset; {@code headerTemplate}
     * may contain {@code {lines}} and {@code {keys}}.
     */
    public static FilterStage fromDef(FilterOverrideConfig.StageDef stageDef) {
        String key = stageDef.key() != null ? stageDef.key() : KEY_PREFIX_BEFORE_COLON;
        String header = stageDef.header() != null ? stageDef.header() : "{lines}";
        int topN = stageDef.topN() != null && stageDef.topN() > 0 ? stageDef.topN() : 10;
        return ofPreset(key, header, topN);
    }

    public static void validate(String location, FilterOverrideConfig.StageDef stage, List<String> errors) {
        String key = stage.key() != null ? stage.key().trim().toLowerCase(Locale.ROOT) : "";
        if (!KEY_PREFIX_BEFORE_COLON.equals(key) && !KEY_FILE_EXTENSION.equals(key)) {
            errors.add(location + ": 'key' must be '" + KEY_PREFIX_BEFORE_COLON
                + "' or '" + KEY_FILE_EXTENSION + "'");
        }
        if (stage.header() == null || stage.header().isBlank()) {
            errors.add(location + ": 'header' must not be empty");
        }
        if (stage.topN() != null && (stage.topN() < 1 || stage.topN() > 10000)) {
            errors.add(location + ": 'top_n' must be between 1 and 10000, got: " + stage.topN());
        }
    }

    public static AggregateByKeyStage ofPreset(String key, String headerTemplate, int topN) {
        Function<String, String> keyOf = switch (key == null ? "" : key.trim().toLowerCase()) {
            case KEY_PREFIX_BEFORE_COLON -> line -> {
                int colon = line.indexOf(':');
                return colon > 0 ? line.substring(0, colon) : "(stdin)";
            };
            case KEY_FILE_EXTENSION -> line -> {
                int dot = line.lastIndexOf('.');
                return dot >= 0 ? line.substring(dot) : "(no extension)";
            };
            default -> throw new IllegalArgumentException(
                "Unknown aggregate_by_key preset: " + key
                    + ". Allowed: " + KEY_PREFIX_BEFORE_COLON + ", " + KEY_FILE_EXTENSION);
        };
        String template = headerTemplate != null ? headerTemplate : "";
        HeaderFormatter formatter = (lines, keys) -> template
            .replace("{lines}", Integer.toString(lines))
            .replace("{keys}", Integer.toString(keys));
        return new AggregateByKeyStage(keyOf, formatter, topN);
    }

    @Override
    public StageResult process(String input, FilterContext context) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int lineCount = 0;
        for (String line : (input != null ? input : "").lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            lineCount++;
            counts.merge(keyOf.apply(line), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder(header.format(lineCount, counts.size()));
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(topN)
            .forEach(e -> sb.append("  ").append(e.getKey()).append(": ")
                .append(e.getValue()).append('\n'));
        return StageResult.continueWith(sb.toString().stripTrailing());
    }
}
