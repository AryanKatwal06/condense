package com.condense.filter.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Multiset line difference using the same {@code \\R} splitter as {@code Provenance}.
 *
 * <p>A changed line is one drop plus one add. Identities:
 * {@code input = kept + dropped} and {@code output = kept + added}.
 */
public record LineDiff(
    List<String> kept,
    List<String> dropped,
    List<String> added
) {
    public LineDiff {
        kept = kept == null ? List.of() : List.copyOf(kept);
        dropped = dropped == null ? List.of() : List.copyOf(dropped);
        added = added == null ? List.of() : List.copyOf(added);
    }

    public static String[] split(String text) {
        return (text == null ? "" : text).split("\\R", -1);
    }

    public static LineDiff of(String input, String output) {
        String[] inLines = split(input);
        String[] outLines = split(output);

        Map<String, Integer> outRemaining = counts(outLines);
        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (String line : inLines) {
            int left = outRemaining.getOrDefault(line, 0);
            if (left > 0) {
                kept.add(line);
                outRemaining.put(line, left - 1);
            } else {
                dropped.add(line);
            }
        }

        Map<String, Integer> inRemaining = counts(inLines);
        List<String> added = new ArrayList<>();
        for (String line : outLines) {
            int left = inRemaining.getOrDefault(line, 0);
            if (left > 0) {
                inRemaining.put(line, left - 1);
            } else {
                added.add(line);
            }
        }
        return new LineDiff(kept, dropped, added);
    }

    public int inputLines() {
        return kept.size() + dropped.size();
    }

    public int outputLines() {
        return kept.size() + added.size();
    }

    public int droppedLines() {
        return dropped.size();
    }

    public int addedLines() {
        return added.size();
    }

    public int keptLines() {
        return kept.size();
    }

    private static Map<String, Integer> counts(String[] lines) {
        Map<String, Integer> counts = new HashMap<>();
        for (String line : lines) {
            counts.merge(line, 1, Integer::sum);
        }
        return counts;
    }
}
