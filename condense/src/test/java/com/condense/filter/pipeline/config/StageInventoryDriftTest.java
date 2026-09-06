package com.condense.filter.pipeline.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class StageInventoryDriftTest {

    @Test
    void committedMarkdownMatchesGeneratedResource() throws Exception {
        String expected = StageInventoryWriter.renderMarkdown(StageInventoryWriter.readInventory())
            .replace("\r\n", "\n");
        String actual = Files.readString(StageInventoryWriter.findCommitted(), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        assertThat(actual).isEqualTo(expected);
    }
}
