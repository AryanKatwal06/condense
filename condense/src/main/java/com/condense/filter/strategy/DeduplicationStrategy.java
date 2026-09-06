package com.condense.filter.strategy;

import com.condense.annotation.DeclarativeStage;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideConfig;

import java.util.ArrayList;
import java.util.List;

@DeclarativeStage(aliases = {"deduplication", "dedup"}, capability = "REDUCE", factory = "fromDef")
public final class DeduplicationStrategy implements FilterStage {

    public static final DeduplicationStrategy DEFAULT = new DeduplicationStrategy(50);

    private final int windowSize;

    public DeduplicationStrategy() {
        this(50);
    }

    public DeduplicationStrategy(int windowSize) {
        this.windowSize = windowSize;
    }

    public static FilterStage fromDef(FilterOverrideConfig.StageDef stageDef) {
        int window = (stageDef.windowSize() != null && stageDef.windowSize() > 0)
            ? stageDef.windowSize() : 50;
        return new DeduplicationStrategy(window);
    }

    public static void validate(String location, FilterOverrideConfig.StageDef stage, List<String> errors) {
        if (stage.windowSize() != null && (stage.windowSize() <= 0 || stage.windowSize() > 10000)) {
            errors.add(location + ": 'window_size' must be between 1 and 10000, got: " + stage.windowSize());
        }
    }

    private static final java.util.regex.Pattern MULTIPLIER_PATTERN = java.util.regex.Pattern.compile("\\s+\\(×\\d+\\)$");

    @Override
    public StageResult process(String input, FilterContext context) {
        if (input == null || input.isEmpty()) {
            return StageResult.continueWith("");
        }
        List<String> lines = input.lines().toList();
        List<String> deduped = deduplicate(lines, windowSize);
        return StageResult.continueWith(String.join("\n", deduped));
    }

    /**
     * Deduplicates lines within a sliding window of {@code windowSize} lines.
     *
     * @param lines      the input lines
     * @param windowSize how far back to look for a matching line (default: 50)
     * @return deduplicated lines; lines appearing N times become "line (×N)"
     */
    public static List<String> deduplicate(List<String> lines, int windowSize) {
        if (lines == null || lines.isEmpty()) return List.of();

        List<String> result = new ArrayList<>(lines.size());
        // track: line content → index in result list (within window)
        java.util.LinkedHashMap<String, int[]> seen =
            new java.util.LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, int[]> eldest) {
                    return size() > windowSize;
                }
            };

        for (String line : lines) {
            String key = line.trim();
            if (seen.containsKey(key)) {
                int[] entry = seen.get(key);
                int resultIdx = entry[0];
                int count = ++entry[1];
                // Update the result line in-place with new count
                String base = result.get(resultIdx);
                // Strip old "(×N)" suffix if present
                String stripped = BoundedRegex.replaceAll(
                    MULTIPLIER_PATTERN, base, "", BoundedRegex.DOCUMENT_TIMEOUT_MS).stripTrailing();
                result.set(resultIdx, stripped + " (×" + count + ")");
            } else {
                int[] entry = {result.size(), 1};
                seen.put(key, entry);
                result.add(line);
            }
        }
        return result;
    }

    /** Deduplicates with the default window of 50 lines. */
    public static List<String> deduplicate(List<String> lines) {
        return deduplicate(lines, 50);
    }
}