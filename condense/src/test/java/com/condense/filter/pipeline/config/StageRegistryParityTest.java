package com.condense.filter.pipeline.config;

import com.condense.filter.pipeline.NamedStage;
import com.condense.trust.Capability;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StageRegistryParityTest {

    @Test
    void generatedCoversFrozenLegacyAliasesWithSameBehavior() {
        assertThat(StageFactory.ALLOWED_ALIASES).containsAll(LegacyStageFactory.ALLOWED_ALIASES);
        for (String alias : LegacyStageFactory.ALLOWED_ALIASES) {
            assertThat(StageFactory.capabilityOf(alias))
                .as(alias)
                .isEqualTo(LegacyStageFactory.capabilityOf(alias));
            FilterOverrideConfig.StageDef def = sample(alias);
            assertThat(typeName(StageFactory.instantiate(def)))
                .as(alias)
                .isEqualTo(typeName(LegacyStageFactory.instantiate(def)));
            List<String> generated = new ArrayList<>();
            List<String> legacy = new ArrayList<>();
            StageFactory.validate("[stage]", def, generated);
            LegacyStageFactory.validate("[stage]", def, legacy);
            assertThat(generated).as(alias).isEqualTo(legacy);
        }
    }

    @Test
    void stageFactoryHasNoStrategySwitch() throws Exception {
        Path source = findStageFactory();
        String text = Files.readString(source);
        assertThat(text).doesNotContain("case \"ansi_strip\"");
        assertThat(text).doesNotContain("private static FilterStage instantiateRaw");
    }

    @Test
    void unknownClassNameCannotInstantiate() {
        FilterOverrideConfig.StageDef def = new FilterOverrideConfig.StageDef(
            "com.condense.filter.stage.Fake", null, null, null, null, List.of(), java.util.Map.of(),
            null, null, null, null, null, null, null, null, null, null);
        List<String> errors = new ArrayList<>();
        StageFactory.validate("[stage]", def, errors);
        assertThat(errors.getFirst()).contains("Unknown strategy");
        assertThat(StageFactory.instantiate(def)).isNull();
        assertThat(StageFactory.capabilityOf("com.condense.filter.stage.Fake")).isEqualTo(Capability.RESHAPE);
    }

    private static String typeName(Object stage) {
        if (stage == null) {
            return "null";
        }
        return NamedStage.unwrap((com.condense.filter.pipeline.FilterStage) stage).getClass().getName();
    }

    private static Path findStageFactory() {
        Path direct = Path.of("src/main/java/com/condense/filter/pipeline/config/StageFactory.java");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path nested = Path.of("condense").resolve(direct);
        if (Files.isRegularFile(nested)) {
            return nested;
        }
        throw new AssertionError("StageFactory.java not found");
    }

    private static FilterOverrideConfig.StageDef sample(String alias) {
        String canonical = LegacyStageFactory.canonicalAlias(alias);
        return switch (canonical) {
            case "tail_lines" -> new FilterOverrideConfig.StageDef(
                alias, null, null, null, null, List.of(), java.util.Map.of(),
                10, null, null, null, null, null, null, null, null, null);
            case "head_tail" -> new FilterOverrideConfig.StageDef(
                alias, null, null, null, null, List.of(), java.util.Map.of(),
                null, null, null, 1, 1, null, null, null, null, null);
            case "aggregate_by_key" -> new FilterOverrideConfig.StageDef(
                alias, null, null, null, null, List.of(), java.util.Map.of(),
                null, null, null, null, null, "prefix_before_colon", "{lines}", 10, null, null);
            case "regex_capture" -> new FilterOverrideConfig.StageDef(
                alias, null, "(.*)", null, null, List.of(), java.util.Map.of(),
                null, null, null, null, null, null, null, null, "$1", "");
            case "grouping" -> new FilterOverrideConfig.StageDef(
                alias, null, "(.*)", false, null, List.of(), java.util.Map.of(),
                null, null, null, null, null, null, null, null, null, null);
            case "state_machine" -> new FilterOverrideConfig.StageDef(
                alias, null, null, null, "START",
                List.of(new FilterOverrideConfig.TransitionDef("START", ".", "EMIT", "START")),
                java.util.Map.of(),
                null, null, null, null, null, null, null, null, null, null);
            case "deduplication" -> new FilterOverrideConfig.StageDef(
                alias, 50, null, null, null, List.of(), java.util.Map.of(),
                null, null, null, null, null, null, null, null, null, null);
            default -> new FilterOverrideConfig.StageDef(
                alias, null, null, null, null, List.of(), java.util.Map.of(),
                null, null, null, null, null, null, null, null, null, null);
        };
    }
}
