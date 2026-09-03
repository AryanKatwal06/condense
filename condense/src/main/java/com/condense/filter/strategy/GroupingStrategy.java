package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class GroupingStrategy implements FilterStage {

    private final Pattern keyPattern;
    private final boolean includeOther;
    private final long timeoutMillis;

    public GroupingStrategy() {
        this(Pattern.compile("(.*)"), false, 0);
    }

    public GroupingStrategy(Pattern keyPattern) {
        this(keyPattern, false, 0);
    }

    public GroupingStrategy(Pattern keyPattern, boolean includeOther) {
        this(keyPattern, includeOther, 0);
    }

    public GroupingStrategy(Pattern keyPattern, boolean includeOther, long timeoutMillis) {
        this.keyPattern = keyPattern;
        this.includeOther = includeOther;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public StageResult process(String input, FilterContext context) {
        if (input == null || input.isEmpty()) {
            return StageResult.continueWith("");
        }
        List<String> lines = input.lines().toList();
        Map<String, Integer> groups = group(lines, keyPattern, includeOther, timeoutMillis);
        return StageResult.continueWith(format(groups));
    }

    /**
     * Groups {@code lines} by the string captured in group 1 of {@code keyPattern}.
     * Lines not matching the pattern are counted under the key {@code "(other)"}
     * if {@code includeOther} is true, or silently discarded otherwise.
     *
     * @param lines        input lines
     * @param keyPattern   regex with one capture group that extracts the group key
     * @param includeOther whether non-matching lines count toward "(other)"
     * @return map of key → count, sorted by count descending, then key ascending
     */
    public static Map<String, Integer> group(
            List<String> lines, Pattern keyPattern, boolean includeOther) {
        return group(lines, keyPattern, includeOther, 0);
    }

    /**
     * Groups {@code lines} by the string captured in group 1 of {@code keyPattern},
     * enforcing a per-match timeout if {@code timeoutMillis > 0}.
     *
     * @param lines         input lines
     * @param keyPattern    regex with one capture group that extracts the group key
     * @param includeOther  whether non-matching lines count toward "(other)"
     * @param timeoutMillis maximum execution time per regex match in milliseconds, or 0 for unconstrained
     * @return map of key → count, sorted by count descending, then key ascending
     */
    public static Map<String, Integer> group(
            List<String> lines, Pattern keyPattern, boolean includeOther, long timeoutMillis) {

        Map<String, Integer> counts = new LinkedHashMap<>();
        int other = 0;

        for (String line : lines) {
            CharSequence lineSeq = timeoutMillis > 0
                ? new TimeoutCharSequence(line, timeoutMillis, keyPattern.pattern())
                : line;
            var m = keyPattern.matcher(lineSeq);
            if (m.find()) {
                String key = m.group(1).trim();
                counts.merge(key, 1, Integer::sum);
            } else if (includeOther) {
                other++;
            }
        }

        if (includeOther && other > 0) counts.put("(other)", other);

        // Sort by count descending, then key ascending for stability
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (a, b) -> a,
                LinkedHashMap::new));
    }

    /**
     * Formats a frequency map as a compact indented block:
     * <pre>
     *   rule-name           : 14
     *   another-rule        :  3
     * </pre>
     */
    public static String format(Map<String, Integer> groups) {
        if (groups.isEmpty()) return "";
        int maxKeyLen = groups.keySet().stream()
            .mapToInt(String::length).max().orElse(0);
        int maxCount = groups.values().stream()
            .mapToInt(Integer::intValue).max().orElse(0);
        int countWidth = String.valueOf(maxCount).length();

        StringBuilder sb = new StringBuilder();
        for (var entry : groups.entrySet()) {
            sb.append(String.format("  %-" + maxKeyLen + "s : %" + countWidth + "d%n",
                entry.getKey(), entry.getValue()));
        }
        return sb.toString().stripTrailing();
    }
}