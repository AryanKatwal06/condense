package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits the first {@code head} and last {@code tail} lines of a large text body.
 */
public final class HeadTailStage implements FilterStage {

    private final int head;
    private final int tail;

    public HeadTailStage(int head, int tail) {
        if (head < 0 || tail < 0) {
            throw new IllegalArgumentException("head and tail must be >= 0");
        }
        this.head = head;
        this.tail = tail;
    }

    @Override
    public StageResult process(String input, FilterContext context) {
        String text = input != null ? input : "";
        List<String> lines = text.lines().toList();
        int omit = lines.size() - head - tail;
        if (omit <= 0) {
            return StageResult.continueWith(text);
        }
        List<String> first = lines.subList(0, head);
        List<String> last = lines.subList(lines.size() - tail, lines.size());
        StringBuilder sb = new StringBuilder();
        for (String line : first) {
            sb.append(line).append('\n');
        }
        sb.append("... (").append(omit).append(" lines omitted) ...\n");
        for (String line : last) {
            sb.append(line).append('\n');
        }
        return StageResult.continueWith(sb.toString().stripTrailing());
    }

    /**
     * First {@code keep} and last {@code keep} lines collected in one streaming pass,
     * matching {@code CatFilter}'s original window.
     */
    public static HeadTailSnapshot snapshot(java.util.stream.Stream<String> stream, int keep) {
        List<String> first = new ArrayList<>();
        List<String> last = new ArrayList<>();
        int[] count = {0};
        stream.forEach(line -> {
            if (count[0] < keep) {
                first.add(line);
            }
            if (last.size() >= keep) {
                last.remove(0);
            }
            last.add(line);
            count[0]++;
        });
        return new HeadTailSnapshot(List.copyOf(first), List.copyOf(last), count[0]);
    }

    public record HeadTailSnapshot(List<String> first, List<String> last, int count) {}
}
