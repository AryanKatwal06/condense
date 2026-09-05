package com.condense.filter.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-stage accounting produced by {@link FilterPipeline#executeTraced}.
 */
public record StageTrace(
    String id,
    String status,
    int inputLines,
    int outputLines,
    int inputTokens,
    int outputTokens,
    int droppedLines,
    int addedLines,
    int keptLines,
    boolean shortCircuit,
    List<String> droppedSample,
    List<String> addedSample,
    boolean droppedTruncated,
    boolean addedTruncated,
    String detail
) {
    public static final String RAN = "ran";
    public static final String SHORT_CIRCUITED = "short_circuited";
    public static final String SKIPPED = "skipped";
    public static final String EXCEPTION = "exception";

    public StageTrace {
        id = id == null ? "" : id;
        status = status == null ? RAN : status;
        droppedSample = copyMutable(droppedSample);
        addedSample = copyMutable(addedSample);
    }

    public static StageTrace skipped(String id) {
        return new StageTrace(
            id, SKIPPED, 0, 0, 0, 0, 0, 0, 0, false,
            new ArrayList<>(), new ArrayList<>(), false, false, null
        );
    }

    public static StageTrace of(
            String id,
            String status,
            String input,
            String output,
            boolean shortCircuit,
            String detail,
            int sampleLimit
    ) {
        LineDiff diff = LineDiff.of(input, output);
        Sample dropped = sample(diff.dropped(), sampleLimit);
        Sample added = sample(diff.added(), sampleLimit);
        return new StageTrace(
            id,
            status,
            diff.inputLines(),
            diff.outputLines(),
            com.condense.core.TokenCounter.count(input),
            com.condense.core.TokenCounter.count(output),
            diff.droppedLines(),
            diff.addedLines(),
            diff.keptLines(),
            shortCircuit,
            dropped.lines(),
            added.lines(),
            dropped.truncated(),
            added.truncated(),
            detail
        );
    }

    private static Sample sample(List<String> lines, int limit) {
        if (limit <= 0 || lines == null || lines.isEmpty()) {
            return new Sample(new ArrayList<>(), limit <= 0 && lines != null && !lines.isEmpty());
        }
        if (lines.size() <= limit) {
            return new Sample(new ArrayList<>(lines), false);
        }
        return new Sample(new ArrayList<>(lines.subList(0, limit)), true);
    }

    private static List<String> copyMutable(List<String> lines) {
        return lines == null ? new ArrayList<>() : new ArrayList<>(lines);
    }

    private record Sample(List<String> lines, boolean truncated) {}
}
