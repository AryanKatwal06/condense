package com.condense.read;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommentStripPropertyTest {

    @Test
    void commentStripNeverDropsNonWhitespaceKeepCharacters() {
        LanguageDefinitionCatalog catalog = LanguageDefinitionCatalog.standalone();
        List<String> failures = new ArrayList<>();
        for (CompiledLanguage language : catalog.all()) {
            for (LanguageDefinition.InlineTest test : language.definition().tests()) {
                if (test.input() == null) {
                    continue;
                }
                SourceScanner.Classification classified = SourceScanner.classify(test.input(), language);
                String stripped = ReadRenderer.joinPlain(ReadRenderer.commentStrippedLines(classified));
                String keep = classified.nonWhitespaceKeep();
                String strippedKeep = nonWhitespace(stripped);
                if (!keep.equals(strippedKeep)) {
                    failures.add(language.name() + "/" + test.id()
                        + " dropped KEEP text. keep=" + keep + " stripped=" + strippedKeep);
                }
                String comment = classified.nonWhitespaceComment();
                for (int i = 0; i < comment.length(); ) {
                    int cp = comment.codePointAt(i);
                    if (stripped.indexOf(Character.toString(cp)) >= 0 && !keep.contains(Character.toString(cp))) {
                        failures.add(language.name() + "/" + test.id()
                            + " leaked COMMENT code point U+" + Integer.toHexString(cp));
                    }
                    i += Character.charCount(cp);
                }
            }
        }
        assertThat(failures).isEmpty();
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
