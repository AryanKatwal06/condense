package com.condense.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ReadCorpusTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void corpusFixturesRetainCriticalSignals() throws Exception {
        try (InputStream in = ReadCorpusTest.class.getResourceAsStream("/read-corpus/catalog.json")) {
            assertThat(in).isNotNull();
            JsonNode root = JSON.readTree(in);
            assertThat(root.get("schema_version").asInt()).isEqualTo(1);
            LanguageDefinitionCatalog catalog = LanguageDefinitionCatalog.standalone();
            ReadService service = new ReadService(catalog);
            for (JsonNode entry : root.get("entries")) {
                String fixture = entry.get("fixture").asText();
                try (InputStream raw = ReadCorpusTest.class.getResourceAsStream("/" + fixture)) {
                    assertThat(raw).as(fixture).isNotNull();
                    String source = new String(raw.readAllBytes(), StandardCharsets.UTF_8);
                    CompiledLanguage language = catalog.required(entry.get("language").asText());
                    ReadService.Rendered rendered = service.render(source, language, ReadLevel.COMMENTS);
                    for (JsonNode critical : entry.get("critical")) {
                        assertThat(rendered.body())
                            .as(entry.get("id").asText() + " must keep " + critical.asText())
                            .contains(critical.asText());
                    }
                }
            }
        }
    }
}
