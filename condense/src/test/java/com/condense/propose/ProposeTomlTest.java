package com.condense.propose;

import com.condense.filter.pipeline.config.FilterOverrideConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProposeTomlTest {

    @Test
    void identityFragmentRoundTrips() throws Exception {
        String toml = ProposeToml.fragment("weirdtool", List.of());
        FilterOverrideConfig.FileConfig parsed = ProposeToml.parse("schema_version = 1\n\n" + toml);
        assertThat(parsed.filters()).containsKey("weirdtool");
        assertThat(parsed.filters().get("weirdtool").stages()).isEmpty();
    }

    @Test
    void unmatchedStagesRoundTrip() throws Exception {
        String toml = ProposeToml.fragment("weirdtool", ProposeToml.unmatchedStages(40));
        FilterOverrideConfig.FileConfig parsed = ProposeToml.parse("schema_version = 1\n\n" + toml);
        List<FilterOverrideConfig.StageDef> stages = parsed.filters().get("weirdtool").stages();
        assertThat(stages).hasSize(2);
        assertThat(stages.get(0).strategy()).isEqualTo("ansi_strip");
        assertThat(stages.get(1).strategy()).isEqualTo("tail_lines");
        assertThat(stages.get(1).maxLines()).isEqualTo(40);
        assertThat(stages.get(1).skipBlank()).isTrue();
    }

    @Test
    void groupingPatternEscapesAndRoundTrips() throws Exception {
        FilterOverrideConfig.StageDef grouping = new FilterOverrideConfig.StageDef(
            "grouping", null, "^(.*(Packages:|ERR_).*)$", false, null, List.of(), Map.of(),
            null, null, null, null, null, null, null, null, null, null);
        String toml = ProposeToml.fragment("pnpm install", List.of(
            ProposeToml.strategy("ansi_strip"), grouping));
        FilterOverrideConfig.FileConfig parsed = ProposeToml.parse("schema_version = 1\n\n" + toml);
        FilterOverrideConfig.StageDef copied = parsed.filters().get("pnpm install").stages().get(1);
        assertThat(copied.pattern()).isEqualTo("^(.*(Packages:|ERR_).*)$");
        assertThat(copied.includeOther()).isFalse();
    }

    @Test
    void documentIsDeterministic() {
        Map<String, List<FilterOverrideConfig.StageDef>> filters = new LinkedHashMap<>();
        filters.put("aaa", List.of());
        filters.put("bbb", ProposeToml.unmatchedStages(40));
        assertThat(ProposeToml.document(filters)).isEqualTo(ProposeToml.document(filters));
        assertThat(ProposeToml.document(filters)).startsWith("schema_version = 1\n");
    }
}
