package com.condense.filter.pipeline;

import com.condense.filter.strategy.AnsiStripStrategy;
import com.condense.filter.strategy.HeadTailStage;
import com.condense.filter.strategy.StateMachineStrategy;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementalStageSessionTest {

    @Test
    void ansiStripSessionMatchesProcess() {
        AnsiStripStrategy stage = AnsiStripStrategy.INSTANCE;
        String raw = "\u001B[31mred\u001B[0m\nplain\n";
        assertThat(replay(stage, raw)).isEqualTo(stage.process(raw).output());
        assertThat(stage.streamability()).isEqualTo(Streamability.ORDER_LOCAL);
    }

    @Test
    void headTailSessionMatchesProcessWhenTruncating() {
        HeadTailStage stage = new HeadTailStage(2, 2);
        String raw = "a\nb\nc\nd\ne\nf";
        assertThat(replay(stage, raw)).isEqualTo(stage.process(raw).output());
        assertThat(stage.streamability()).isEqualTo(Streamability.WINDOWED);
    }

    @Test
    void headTailSessionMatchesProcessWhenShort() {
        HeadTailStage stage = new HeadTailStage(2, 2);
        String raw = "a\nb\nc";
        assertThat(replay(stage, raw)).isEqualTo(stage.process(raw).output());
    }

    @Test
    void stateMachineSessionMatchesProcess() {
        StateMachineStrategy stage = StateMachineStrategy.builder("START")
            .on("START", Pattern.compile("keep"), StateMachineStrategy.Action.EMIT, "START")
            .defaultAction("START", StateMachineStrategy.Action.DISCARD)
            .build();
        String raw = "keep this\ndrop\nkeep that";
        assertThat(replay(stage, raw)).isEqualTo(stage.process(raw).output());
        assertThat(stage.streamability()).isEqualTo(Streamability.ORDER_LOCAL);
    }

    private static String replay(FilterStage stage, String raw) {
        CollectingSink sink = new CollectingSink();
        stage.openSession().acceptDocument(raw, sink, FilterContext.empty());
        return sink.output();
    }
}
