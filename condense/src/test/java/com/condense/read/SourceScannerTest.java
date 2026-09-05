package com.condense.read;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceScannerTest {

    private final LanguageDefinitionCatalog catalog = LanguageDefinitionCatalog.standalone();

    @Test
    void javascriptKeepsGlobStringAndDropsBlockComment() {
        CompiledLanguage js = catalog.required("javascript");
        String source = """
            let glob = "src/**/*";
            /* drop me */
            const y = 1;
            """;
        SourceScanner.Classification classified = SourceScanner.classify(source, js);
        String kept = ReadRenderer.joinPlain(ReadRenderer.commentStrippedLines(classified));
        assertThat(kept).contains("src/**/*");
        assertThat(kept).contains("const y = 1;");
        assertThat(kept).doesNotContain("drop me");
    }

    @Test
    void jsonNeverTreatsStarSlashAsComment() {
        CompiledLanguage json = catalog.required("json");
        String source = "{\"workspaces\": [\"packages/*\"]}";
        SourceScanner.Classification classified = SourceScanner.classify(source, json);
        assertThat(classified.keepText()).isEqualTo(source);
        assertThat(classified.nonWhitespaceComment()).isEmpty();
    }

    @Test
    void unknownLanguageIsNotScanned() {
        String source = "let glob = \"src/**/*\";\n/* still here */\n";
        SourceScanner.Classification classified = SourceScanner.classify(source, null);
        assertThat(classified.keepText()).isEqualTo(source);
    }

    @Test
    void rustRawStringKeepsHashStar() {
        CompiledLanguage rust = catalog.required("rust");
        String source = "let glob = r#\"src/**/*\"#;\n/* gone */\nfn ok() {}\n";
        String kept = ReadRenderer.joinPlain(
            ReadRenderer.commentStrippedLines(SourceScanner.classify(source, rust)));
        assertThat(kept).contains("src/**/*");
        assertThat(kept).doesNotContain("gone");
    }
}
