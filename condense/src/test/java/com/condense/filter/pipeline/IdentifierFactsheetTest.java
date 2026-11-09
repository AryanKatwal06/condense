package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdentifierFactsheetTest {

    private static final String RAW = """
        uuid 550e8400-e29b-41d4-a716-446655440000
        advisory CVE-2026-41802
        ticket PROJ-1482
        commit 3f2ec81ea3e4
        encoding UTF-8
        digest SHA-256
        standard ISO-8601
        """;

    @Test
    void extractsDroppedUuidsCvesTicketsAndShas() {
        List<String> identifiers = IdentifierFactsheet.extractDroppedIdentifiers(RAW, "");

        assertThat(identifiers)
            .contains("550e8400-e29b-41d4-a716-446655440000")
            .contains("CVE-2026-41802")
            .contains("PROJ-1482")
            .contains("3f2ec81ea3e4");
    }

    @Test
    void ignoresNoiseAndUuidSegments() {
        List<String> identifiers = IdentifierFactsheet.extractDroppedIdentifiers(RAW, "");

        assertThat(identifiers)
            .doesNotContain("UTF-8", "SHA-256", "ISO-8601", "HTTP-1")
            .doesNotContain("550e8400", "446655440000", "CVE-2026");
    }

    @Test
    void excludesIdentifiersAlreadyVisibleInFilteredOutput() {
        String filtered = "uuid 550e8400-e29b-41d4-a716-446655440000\ncommit 3f2ec81ea3e4\n";

        List<String> identifiers = IdentifierFactsheet.extractDroppedIdentifiers(RAW, filtered);

        assertThat(identifiers)
            .doesNotContain("550e8400-e29b-41d4-a716-446655440000", "3f2ec81ea3e4")
            .contains("CVE-2026-41802", "PROJ-1482");
    }

    @Test
    void capsIdentifiersAtTwelve() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            raw.append(String.format(
                "550e8400-e29b-41d4-a716-%012d%n", i));
        }

        List<String> identifiers = IdentifierFactsheet.extractDroppedIdentifiers(raw.toString(), "");

        assertThat(identifiers).hasSize(IdentifierFactsheet.MAX_IDENTIFIERS);
    }

    @Test
    void formatsFactsheetLineWithinBudget() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(String.format("3f2ec81ea3e4%02d", i));
        }

        String line = IdentifierFactsheet.formatFactsheetLine(ids);

        assertThat(line).startsWith("[preserved ids: ").endsWith("]");
        assertThat(line.length()).isLessThanOrEqualTo(IdentifierFactsheet.MAX_FACTSHEET_CHARS);
        assertThat(line.chars().filter(c -> c == ',').count())
            .isLessThan(IdentifierFactsheet.MAX_IDENTIFIERS);
    }

    @Test
    void formatsEmptyFactsheetAsEmptyString() {
        assertThat(IdentifierFactsheet.formatFactsheetLine(List.of())).isEmpty();
        assertThat(IdentifierFactsheet.formatFactsheetLine(null)).isEmpty();
    }

    @Test
    void deterministicAcrossRepeatedCalls() {
        List<String> first = IdentifierFactsheet.extractDroppedIdentifiers(RAW, "");
        List<String> second = IdentifierFactsheet.extractDroppedIdentifiers(RAW, "");

        assertThat(first).containsExactlyElementsOf(second);
    }

    @Test
    void pipelineAppendsFactsheetForHeavilyCompressedLargeOutput() {
        StringBuilder raw = new StringBuilder();
        raw.append("uuid 550e8400-e29b-41d4-a716-446655440000\n");
        raw.append("ticket PROJ-1482\n");
        raw.append("commit 3f2ec81ea3e4\n");
        while (raw.length() < IdentifierFactsheet.APPEND_MIN_RAW_CHARS + 100) {
            raw.append("filler line that the summarizer removes\n");
        }
        FilterStage summarizer = (input, ctx) -> StageResult.continueWith("summary\n");
        PipelineBackedFilter filter = new PipelineBackedFilter(
            FilterOverrideLoader.standalone(), FilterPipeline.of(summarizer)) {
            @Override
            protected String definitionName() {
                return "factsheet-test";
            }
        };

        FilterResult result = filter.apply(
            "cat big.log",
            new ExecutionResult(0, raw.toString(), "", 10L),
            CondenseConfig.defaults(),
            0,
            false);

        assertThat(result.wasFiltered()).isTrue();
        assertThat(result.output())
            .contains("[preserved ids: ")
            .contains("550e8400-e29b-41d4-a716-446655440000")
            .contains("PROJ-1482")
            .contains("3f2ec81ea3e4");
    }

    @Test
    void pipelineDoesNotAppendFactsheetBelowSizeFloor() {
        StringBuilder raw = new StringBuilder();
        raw.append("uuid 550e8400-e29b-41d4-a716-446655440000\n");
        while (raw.length() < IdentifierFactsheet.APPEND_MIN_RAW_CHARS - 100) {
            raw.append("filler line that the summarizer removes\n");
        }
        FilterStage summarizer = (input, ctx) -> StageResult.continueWith("summary\n");
        PipelineBackedFilter filter = new PipelineBackedFilter(
            FilterOverrideLoader.standalone(), FilterPipeline.of(summarizer)) {
            @Override
            protected String definitionName() {
                return "factsheet-test";
            }
        };

        FilterResult result = filter.apply(
            "cat small.log",
            new ExecutionResult(0, raw.toString(), "", 10L),
            CondenseConfig.defaults(),
            0,
            false);

        assertThat(result.output()).doesNotContain("[preserved ids: ");
    }
}
