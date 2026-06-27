package com.zapproxy.filter.strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapses consecutive and near-consecutive repeated lines into a single line
 * with a repetition count suffix.
 *
 * <p>Example: five identical "ERROR: connection refused" lines become:
 * <pre>ERROR: connection refused (×5)</pre>
 *
 * <p>"Near-consecutive" means within a configurable window (default 50 lines).
 * This handles log patterns where the same error repeats but is separated by
 * stack trace lines.
 */
public final class DeduplicationStrategy {

    private DeduplicationStrategy() {}

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
                String stripped = base.replaceAll("\\s+\\(×\\d+\\)$", "").stripTrailing();
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