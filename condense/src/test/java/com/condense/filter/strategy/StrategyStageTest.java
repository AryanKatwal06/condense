package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyStageTest {

    @Test
    @DisplayName("AnsiStripStrategy works as standalone method and FilterStage")
    void ansiStripStrategy_worksAsStage() {
        String raw = "\u001B[32mSUCCESS\u001B[0m in 12ms\roverwriting line\rFinal line";
        String expected = AnsiStripStrategy.strip(raw);

        AnsiStripStrategy stage = new AnsiStripStrategy();
        StageResult result = stage.process(raw, FilterContext.empty());

        assertThat(result.output()).isEqualTo(expected);
        assertThat(result.shortCircuit()).isFalse();
        assertThat(AnsiStripStrategy.INSTANCE.process(raw).output()).isEqualTo(expected);
    }

    @Test
    @DisplayName("DeduplicationStrategy works as standalone method and FilterStage")
    void deduplicationStrategy_worksAsStage() {
        List<String> lines = List.of("warning: deprecated", "warning: deprecated", "info: ok");
        String input = String.join("\n", lines);
        String expected = String.join("\n", DeduplicationStrategy.deduplicate(lines, 50));

        DeduplicationStrategy stage = new DeduplicationStrategy(50);
        StageResult result = stage.process(input, FilterContext.empty());

        assertThat(result.output()).isEqualTo(expected);
        assertThat(result.output()).contains("warning: deprecated (×2)");
    }

    @Test
    @DisplayName("GroupingStrategy works as standalone method and FilterStage")
    void groupingStrategy_worksAsStage() {
        Pattern pattern = Pattern.compile("rule-([a-z]+)");
        List<String> lines = List.of("rule-alpha error", "rule-beta error", "rule-alpha warning");
        String input = String.join("\n", lines);
        var groupMap = GroupingStrategy.group(lines, pattern, false);
        String expected = GroupingStrategy.format(groupMap);

        GroupingStrategy stage = new GroupingStrategy(pattern, false);
        StageResult result = stage.process(input, FilterContext.empty());

        assertThat(result.output()).isEqualTo(expected);
        assertThat(result.output()).contains("alpha");
        assertThat(result.output()).contains("beta");
    }

    @Test
    @DisplayName("JsonStructureStrategy works as standalone method and FilterStage")
    void jsonStructureStrategy_worksAsStage() {
        String json = "{\"name\": \"test\", \"count\": 42, \"items\": [\"a\", \"b\", \"c\"]}";
        String expected = JsonStructureStrategy.skeleton(json);

        JsonStructureStrategy stage = new JsonStructureStrategy();
        StageResult result = stage.process(json, FilterContext.empty());

        assertThat(result.output()).isEqualTo(expected);
        assertThat(result.output()).contains("<string>");
        assertThat(result.output()).contains("0");
    }

    @Test
    @DisplayName("StateMachineStrategy works as standalone method and FilterStage")
    void stateMachineStrategy_worksAsStage() {
        StateMachineStrategy sm = StateMachineStrategy.builder("START")
            .on("START", Pattern.compile("^HEADER"), StateMachineStrategy.Action.DISCARD, "BODY")
            .on("BODY", Pattern.compile("^KEEP"), StateMachineStrategy.Action.EMIT, "BODY")
            .on("BODY", Pattern.compile("^IGNORE"), StateMachineStrategy.Action.DISCARD, "BODY")
            .build();

        List<String> lines = List.of("HEADER", "KEEP 1", "IGNORE 1", "KEEP 2");
        String input = String.join("\n", lines);
        String expected = String.join("\n", sm.process(lines));

        StageResult result = sm.process(input, FilterContext.empty());

        assertThat(result.output()).isEqualTo(expected);
        assertThat(result.output()).isEqualTo("KEEP 1\nKEEP 2");
    }

    @Test
    @DisplayName("TreeCompressionStrategy works as standalone method and FilterStage")
    void treeCompressionStrategy_worksAsStage() {
        List<String> paths = List.of("src/main/App.java", "src/main/Util.java", "src/test/AppTest.java");
        String input = String.join("\n", paths);
        String expected = TreeCompressionStrategy.compress(paths);

        TreeCompressionStrategy stage = new TreeCompressionStrategy();
        StageResult result = stage.process(input, FilterContext.empty());

        assertThat(result.output()).isEqualTo(expected);
        assertThat(result.output()).contains("src/");
    }

    @Test
    @DisplayName("Multiple strategies composed within a FilterPipeline")
    void multipleStrategies_composedInPipeline() {
        // Stage 1: Strip ANSI
        // Stage 2: Deduplicate repeated lines
        FilterPipeline pipeline = FilterPipeline.of(
            new AnsiStripStrategy(),
            new DeduplicationStrategy(10)
        );

        String raw = "\u001B[33mwarning: unused var\u001B[0m\n\u001B[33mwarning: unused var\u001B[0m\nclean line";
        String processed = pipeline.execute(raw);

        assertThat(processed).contains("warning: unused var (×2)");
        assertThat(processed).doesNotContain("\u001B[");
        assertThat(processed).contains("clean line");
    }

    @Test
    @DisplayName("TailLinesStage prefixes a truncation notice")
    void tailLinesStage_truncatesWithHeader() {
        String input = "a\nb\nc\nd\ne";
        StageResult result = new TailLinesStage(2, false, true).process(input, FilterContext.empty());
        assertThat(result.output()).isEqualTo("... (showing last 2 of 5 lines)\nd\ne");
    }

    @Test
    @DisplayName("HeadTailStage keeps first and last sections")
    void headTailStage_keepsEnds() {
        String input = String.join("\n", java.util.stream.IntStream.rangeClosed(1, 10)
            .mapToObj(i -> "L" + i).toList());
        StageResult result = new HeadTailStage(2, 2).process(input, FilterContext.empty());
        assertThat(result.output()).startsWith("L1\nL2\n");
        assertThat(result.output()).contains("... (6 lines omitted) ...");
        assertThat(result.output()).endsWith("L9\nL10");
    }

    @Test
    @DisplayName("AggregateByKeyStage counts by derived key")
    void aggregateByKeyStage_groups() {
        String input = "a.txt\nb.txt\nc.md\n";
        StageResult result = new AggregateByKeyStage(
            line -> line.substring(line.lastIndexOf('.')),
            (lines, keys) -> lines + " result(s)",
            10
        ).process(input, FilterContext.empty());
        assertThat(result.output()).startsWith("3 result(s)");
        assertThat(result.output()).contains(".txt: 2");
        assertThat(result.output()).contains(".md: 1");
    }
}
