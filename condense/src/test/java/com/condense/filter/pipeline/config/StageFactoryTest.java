package com.condense.filter.pipeline.config;

import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.NamedStage;
import com.condense.filter.strategy.DockerPsStage;
import com.condense.filter.strategy.GitStatusStage;
import com.condense.filter.strategy.HeadTailStage;
import com.condense.filter.strategy.JsonLinesStage;
import com.condense.filter.strategy.TailLinesStage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StageFactoryTest {

    @Test
    void everyCanonicalAliasInstantiates() {
        for (String alias : StageFactory.canonicalAliases()) {
            FilterOverrideConfig.StageDef def = stage(alias);
            if ("tail_lines".equals(alias)) {
                def = withMaxLines(def, 10);
            } else if ("head_tail".equals(alias)) {
                def = withHeadTail(def, 1, 1);
            } else if ("aggregate_by_key".equals(alias)) {
                def = withAggregate(def);
            } else if ("regex_capture".equals(alias)) {
                def = withCapture(def);
            } else if ("grouping".equals(alias)) {
                def = withPattern(def, "(.*)");
            } else if ("state_machine".equals(alias)) {
                def = withStateMachine(def);
            }
            assertThat(StageFactory.instantiate(def))
                .as(alias)
                .isNotNull();
        }
    }

    @Test
    void unknownAliasIsRejectedAtValidationAndNullAtRuntime() {
        FilterOverrideConfig.StageDef def = stage("java.lang.Runtime");
        List<String> errors = new ArrayList<>();
        StageFactory.validate("[stage]", def, errors);
        assertThat(errors.getFirst()).contains("Unknown strategy");
        assertThat(StageFactory.instantiate(def)).isNull();
    }

    @Test
    void promotedStagesAreConstructible() {
        assertThat(NamedStage.unwrap(StageFactory.instantiate(withMaxLines(stage("tail_lines"), 30))))
            .isInstanceOf(TailLinesStage.class);
        assertThat(NamedStage.unwrap(StageFactory.instantiate(withHeadTail(stage("head_tail"), 5, 5))))
            .isInstanceOf(HeadTailStage.class);
        assertThat(NamedStage.unwrap(StageFactory.instantiate(stage("git_status")))).isSameAs(GitStatusStage.INSTANCE);
        assertThat(NamedStage.unwrap(StageFactory.instantiate(stage("json_lines")))).isSameAs(JsonLinesStage.INSTANCE);
        assertThat(NamedStage.unwrap(StageFactory.instantiate(stage("docker_ps")))).isSameAs(DockerPsStage.INSTANCE);
        assertThat(StageFactory.instantiate(stage("git_status")).stageId()).isEqualTo("git_status");
        assertThat(StageFactory.instantiate(stage("ansi-strip")).stageId()).isEqualTo("ansi_strip");
    }

    @Test
    void aggregateByKeyPresetsMatchGrepAndFindHeaders() {
        FilterPipeline grep = StageFactory.buildPipeline(List.of(withAggregate(
            stage("aggregate_by_key"),
            "prefix_before_colon",
            "{lines} match(es) in {keys} file(s)",
            10)));
        assertThat(grep.execute("a.java: x\na.java: y\nb.java: z\n"))
            .isEqualTo("3 match(es) in 2 file(s)\n  a.java: 2\n  b.java: 1");

        FilterPipeline find = StageFactory.buildPipeline(List.of(withAggregate(
            stage("aggregate_by_key"),
            "file_extension",
            "{lines} result(s)",
            10)));
        assertThat(find.execute("src/a.java\nREADME\n"))
            .isEqualTo("2 result(s)\n  .java: 1\n  (no extension): 1");
    }

    private static FilterOverrideConfig.StageDef stage(String strategy) {
        return new FilterOverrideConfig.StageDef(
            strategy, null, null, null, null, List.of(), java.util.Map.of(),
            null, null, null, null, null, null, null, null, null, null);
    }

    private static FilterOverrideConfig.StageDef withMaxLines(FilterOverrideConfig.StageDef base, int max) {
        return new FilterOverrideConfig.StageDef(
            base.strategy(), null, null, null, null, List.of(), java.util.Map.of(),
            max, null, null, null, null, null, null, null, null, null);
    }

    private static FilterOverrideConfig.StageDef withHeadTail(FilterOverrideConfig.StageDef base, int head, int tail) {
        return new FilterOverrideConfig.StageDef(
            base.strategy(), null, null, null, null, List.of(), java.util.Map.of(),
            null, null, null, head, tail, null, null, null, null, null);
    }

    private static FilterOverrideConfig.StageDef withAggregate(FilterOverrideConfig.StageDef base) {
        return withAggregate(base, "prefix_before_colon", "{lines}", 10);
    }

    private static FilterOverrideConfig.StageDef withAggregate(
        FilterOverrideConfig.StageDef base, String key, String header, int topN
    ) {
        return new FilterOverrideConfig.StageDef(
            base.strategy(), null, null, null, null, List.of(), java.util.Map.of(),
            null, null, null, null, null, key, header, topN, null, null);
    }

    private static FilterOverrideConfig.StageDef withCapture(FilterOverrideConfig.StageDef base) {
        return new FilterOverrideConfig.StageDef(
            base.strategy(), null, "(.*)", null, null, List.of(), java.util.Map.of(),
            null, null, null, null, null, null, null, null, "$1", "");
    }

    private static FilterOverrideConfig.StageDef withPattern(FilterOverrideConfig.StageDef base, String pattern) {
        return new FilterOverrideConfig.StageDef(
            base.strategy(), null, pattern, false, null, List.of(), java.util.Map.of(),
            null, null, null, null, null, null, null, null, null, null);
    }

    private static FilterOverrideConfig.StageDef withStateMachine(FilterOverrideConfig.StageDef base) {
        return new FilterOverrideConfig.StageDef(
            base.strategy(), null, null, null, "START",
            List.of(new FilterOverrideConfig.TransitionDef("START", ".", "EMIT", "START")),
            java.util.Map.of(),
            null, null, null, null, null, null, null, null, null, null);
    }
}
