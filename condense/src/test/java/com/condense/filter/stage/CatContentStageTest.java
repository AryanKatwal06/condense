package com.condense.filter.stage;

import com.condense.core.CondenseConfig;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CatContentStageTest {

    private static FilterContext normal() {
        return FilterContext.of("cat file.txt", null, CondenseConfig.defaults(), 0, false);
    }

    private static FilterContext ultraCompact() {
        return FilterContext.of("cat file.json", null, CondenseConfig.defaults(), 0, true);
    }

    private static String numberedLines(int count) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(i -> "line " + i)
            .collect(Collectors.joining("\n"));
    }

    @Test
    void shortTextPassesThroughUntouched() {
        String raw = "hello\nworld\n";

        StageResult result = CatContentStage.INSTANCE.process(raw, normal());

        assertThat(result.output()).isEqualTo(raw);
    }

    @Test
    void exactlyTwoHundredLinesPassThrough() {
        String raw = numberedLines(CatContentStage.MAX_LINES_WITHOUT_COMPRESSION);

        StageResult result = CatContentStage.INSTANCE.process(raw, normal());

        assertThat(result.output()).isEqualTo(raw);
    }

    @Test
    void twoHundredOneLinesUseWideWindowWithRangeMarker() {
        String raw = numberedLines(201);

        StageResult result = CatContentStage.INSTANCE.process(raw, normal());

        assertThat(result.output())
            .contains("line 1\n")
            .contains("line 120\n")
            .contains("... (21 lines omitted: lines 121..141 of 201) ...")
            .contains("line 142\n")
            .contains("line 201");
        assertThat(result.output().lines().count()).isEqualTo(181);
    }

    @Test
    void smallJsonPassesThroughEvenAboveLineThreshold() {
        String raw = IntStream.rangeClosed(1, 250)
            .mapToObj(i -> "  {\"id\": " + i + "}")
            .collect(Collectors.joining(",\n", "[\n", "\n]"));

        StageResult result = CatContentStage.INSTANCE.process(raw, normal());

        assertThat(result.output()).isEqualTo(raw);
        assertThat(result.output()).doesNotContain("JSON skeleton");
    }

    @Test
    void largeJsonBecomesSkeletonWithExplicitNotice() {
        String raw = IntStream.rangeClosed(1, 3000)
            .mapToObj(i -> "  {\"id\": " + i + ", \"name\": \"item_" + i + "\"}")
            .collect(Collectors.joining(",\n", "[\n", "\n]"));

        StageResult result = CatContentStage.INSTANCE.process(raw, normal());

        assertThat(result.output())
            .contains("... (JSON skeleton,")
            .contains("bytes omitted) ...")
            .contains("\"... +2999 more\"")
            .contains("\"<string>\"");
    }

    @Test
    void smallJsonBecomesSkeletonInUltraCompactMode() {
        String raw = "{\"name\":\"demo\",\"version\":\"1.0.0\"}";

        StageResult result = CatContentStage.INSTANCE.process(raw, ultraCompact());

        assertThat(result.output())
            .contains("JSON skeleton")
            .contains("\"<string>\"")
            .doesNotContain("demo");
    }

    @Test
    void invalidJsonPassesThrough() {
        String raw = "{\"name\": missing quotes}";

        StageResult result = CatContentStage.INSTANCE.process(raw, normal());

        assertThat(result.output()).isEqualTo(raw);
    }
}
