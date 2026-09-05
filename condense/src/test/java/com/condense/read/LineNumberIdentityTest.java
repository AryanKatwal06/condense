package com.condense.read;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LineNumberIdentityTest {

    @Test
    void emittedNumbersAreOriginalSourceLines() {
        CompiledLanguage java = LanguageDefinitionCatalog.standalone().required("java");
        String source = """
            class Foo {
            // skip
                void bar() {
                    String glob = "src/**/*";
                }
            }
            """;
        List<ReadRenderer.KeptLine> lines = ReadRenderer.commentStrippedLines(
            SourceScanner.classify(source, java));
        assertThat(lines).isNotEmpty();
        String[] original = source.split("\\R", -1);
        for (ReadRenderer.KeptLine line : lines) {
            assertThat(line.originalNumber())
                .isBetween(1, original.length);
            String raw = original[line.originalNumber() - 1];
            SourceScanner.Classification one = SourceScanner.classify(raw + "\n", java);
            String stripped = ReadRenderer.joinPlain(ReadRenderer.commentStrippedLines(one));
            assertThat(line.text()).isEqualTo(stripped);
        }
        assertThat(lines.stream().map(ReadRenderer.KeptLine::originalNumber).toList())
            .doesNotContain(2);
        String numbered = ReadRenderer.formatNumbered(lines);
        assertThat(numbered).contains("1| ");
        assertThat(numbered).doesNotContain("2| ");
    }
}
