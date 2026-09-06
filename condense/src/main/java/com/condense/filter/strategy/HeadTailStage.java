package com.condense.filter.strategy;

import com.condense.annotation.DeclarativeStage;
import com.condense.filter.pipeline.EmissionSink;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.StageSession;
import com.condense.filter.pipeline.Streamability;
import com.condense.filter.pipeline.config.FilterOverrideConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Emits the first {@code head} and last {@code tail} lines of a large text body.
 */
@DeclarativeStage(aliases = {"head_tail", "head-tail"}, capability = "REDUCE", factory = "fromDef")
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

    public static FilterStage fromDef(FilterOverrideConfig.StageDef stageDef) {
        int head = stageDef.head() != null ? stageDef.head() : 20;
        int tail = stageDef.tail() != null ? stageDef.tail() : 20;
        return new HeadTailStage(head, tail);
    }

    public static void validate(String location, FilterOverrideConfig.StageDef stage, List<String> errors) {
        if (stage.head() == null || stage.head() < 0) {
            errors.add(location + ": 'head' must be >= 0");
        }
        if (stage.tail() == null || stage.tail() < 0) {
            errors.add(location + ": 'tail' must be >= 0");
        }
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

    @Override
    public Streamability streamability() {
        return Streamability.WINDOWED;
    }

    @Override
    public StageSession openSession() {
        return new Session();
    }

    private final class Session implements StageSession {
        private final List<String> remainder = new ArrayList<>();
        private final Deque<String> last = new ArrayDeque<>();
        private int count;

        @Override
        public void acceptDocument(String text, EmissionSink sink, FilterContext context) {
            StageResult result = process(text, context);
            sink.emitDocument(result.output());
            if (result.shortCircuit()) {
                sink.shortCircuit();
            }
        }

        @Override
        public void feedLine(String line, EmissionSink sink, FilterContext context) {
            String value = line != null ? line : "";
            if (count < head) {
                sink.emit(value);
            } else {
                remainder.add(value);
                if (tail > 0) {
                    if (last.size() >= tail) {
                        last.removeFirst();
                    }
                    last.addLast(value);
                }
            }
            count++;
        }

        @Override
        public void endOfInput(EmissionSink sink, FilterContext context) {
            int omit = count - head - tail;
            if (omit <= 0) {
                for (String line : remainder) {
                    sink.emit(line);
                }
                return;
            }
            sink.emit("... (" + omit + " lines omitted) ...");
            for (String line : last) {
                sink.emit(line);
            }
        }
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
