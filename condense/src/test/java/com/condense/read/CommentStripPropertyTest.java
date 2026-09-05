package com.condense.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommentStripPropertyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void commentStripNeverDropsNonWhitespaceKeepCharacters() {
        LanguageDefinitionCatalog catalog = LanguageDefinitionCatalog.standalone();
        List<String> failures = new ArrayList<>();
        for (CompiledLanguage language : catalog.all()) {
            for (LanguageDefinition.InlineTest test : language.definition().tests()) {
                if (test.input() == null) {
                    continue;
                }
                checkKeepCommentIdentity(language, language.name() + "/" + test.id(), test.input(), failures);
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void readCorpusFixturesKeepCommentIdentity() throws Exception {
        LanguageDefinitionCatalog catalog = LanguageDefinitionCatalog.standalone();
        List<String> failures = new ArrayList<>();
        try (InputStream in = CommentStripPropertyTest.class.getResourceAsStream("/read-corpus/catalog.json")) {
            assertThat(in).isNotNull();
            JsonNode root = JSON.readTree(in);
            for (JsonNode entry : root.get("entries")) {
                String fixture = entry.get("fixture").asText();
                try (InputStream raw = CommentStripPropertyTest.class.getResourceAsStream("/" + fixture)) {
                    assertThat(raw).as(fixture).isNotNull();
                    String source = new String(raw.readAllBytes(), StandardCharsets.UTF_8);
                    CompiledLanguage language = catalog.required(entry.get("language").asText());
                    checkKeepCommentIdentity(language, entry.get("id").asText(), source, failures);
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    private static void checkKeepCommentIdentity(
            CompiledLanguage language,
            String label,
            String input,
            List<String> failures
    ) {
        SourceScanner.Classification classified = SourceScanner.classify(input, language);
        String stripped = ReadRenderer.joinPlain(ReadRenderer.commentStrippedLines(classified));
        String keep = classified.nonWhitespaceKeep();
        String strippedKeep = nonWhitespace(stripped);
        if (!keep.equals(strippedKeep)) {
            failures.add(label + " dropped KEEP text. keep=" + keep + " stripped=" + strippedKeep);
        }
        String comment = classified.nonWhitespaceComment();
        for (int i = 0; i < comment.length(); ) {
            int cp = comment.codePointAt(i);
            if (stripped.indexOf(Character.toString(cp)) >= 0 && !keep.contains(Character.toString(cp))) {
                failures.add(label + " leaked COMMENT code point U+" + Integer.toHexString(cp));
            }
            i += Character.charCount(cp);
        }
    }

    private static String nonWhitespace(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        text.codePoints().filter(cp -> !SourceScanner.isWhitespace(cp)).forEach(out::appendCodePoint);
        return out.toString();
    }
}
