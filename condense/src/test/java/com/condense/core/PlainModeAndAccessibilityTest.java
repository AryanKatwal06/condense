package com.condense.core;

import com.condense.ir.Document;
import com.condense.ir.Documents;
import com.condense.ir.TextRenderer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlainModeAndAccessibilityTest {

    @Test
    void noColorDisablesColorAndStripsAnsi() {
        AccessibilityPolicy policy = AccessibilityPolicy.of(false, Map.of("NO_COLOR", "1"), StandardCharsets.UTF_8);
        assertThat(policy.isColorEnabled()).isFalse();

        String ansiText = "\u001B[32mSuccess\u001B[0m";
        assertThat(policy.format(ansiText)).isEqualTo("Success");
    }

    @Test
    void cliColorZeroDisablesColor() {
        AccessibilityPolicy policy = AccessibilityPolicy.of(false, Map.of("CLICOLOR", "0"), StandardCharsets.UTF_8);
        assertThat(policy.isColorEnabled()).isFalse();

        String ansiText = "\u001B[31mError\u001B[0m";
        assertThat(policy.format(ansiText)).isEqualTo("Error");
    }

    @Test
    void cliColorForceOverridesNoColor() {
        AccessibilityPolicy policy = AccessibilityPolicy.of(
            false,
            Map.of("NO_COLOR", "1", "CLICOLOR_FORCE", "1"),
            StandardCharsets.UTF_8
        );
        assertThat(policy.isColorEnabled()).isTrue();

        String ansiText = "\u001B[32mSuccess\u001B[0m";
        assertThat(policy.format(ansiText)).isEqualTo(ansiText);
    }

    @Test
    void plainFlagOverridesCliColorForceAndForcesAscii() {
        AccessibilityPolicy policy = AccessibilityPolicy.of(
            true, // --plain
            Map.of("CLICOLOR_FORCE", "1"),
            StandardCharsets.UTF_8
        );
        assertThat(policy.isColorEnabled()).isFalse();
        assertThat(policy.isAsciiOnly()).isTrue();
        assertThat(policy.isPlain()).isTrue();

        String raw = "\u001B[32m✓ all tests passed\u001B[0m ✗ failed ─── ↑ █ ▌ ░";
        String formatted = policy.format(raw);
        assertThat(formatted).doesNotContain("\u001B");
        assertThat(formatted).contains("[OK] all tests passed");
        assertThat(formatted).contains("[ERROR] failed");
        assertThat(formatted).contains("---");
        assertThat(formatted).contains("^");
        assertThat(formatted).contains("#");
        assertThat(formatted).contains("|");
        assertThat(formatted).contains("-");
    }

    @Test
    void nonUtf8CharsetForcesAsciiFallback() {
        AccessibilityPolicy policy = AccessibilityPolicy.of(
            false,
            Map.of(),
            StandardCharsets.US_ASCII
        );
        assertThat(policy.isAsciiOnly()).isTrue();

        String input = "✓ clean";
        assertThat(policy.format(input)).isEqualTo("[OK] clean");
    }

    @Test
    void terminalWidthClampingHonorsRules() {
        assertThat(AccessibilityPolicy.clampWidth(0)).isEqualTo(0);
        assertThat(AccessibilityPolicy.clampWidth(-5)).isEqualTo(0);
        assertThat(AccessibilityPolicy.clampWidth(20)).isEqualTo(40);
        assertThat(AccessibilityPolicy.clampWidth(40)).isEqualTo(40);
        assertThat(AccessibilityPolicy.clampWidth(79)).isEqualTo(79);
        assertThat(AccessibilityPolicy.clampWidth(120)).isEqualTo(120);

        AccessibilityPolicy policyNarrow = AccessibilityPolicy.of(false, Map.of("COLUMNS", "25"), StandardCharsets.UTF_8);
        assertThat(policyNarrow.terminalWidth()).isEqualTo(40);

        AccessibilityPolicy policyWide = AccessibilityPolicy.of(false, Map.of("COLUMNS", "100"), StandardCharsets.UTF_8);
        assertThat(policyWide.terminalWidth()).isEqualTo(100);

        AccessibilityPolicy policyUnbounded = AccessibilityPolicy.of(false, Map.of("COLUMNS", "0"), StandardCharsets.UTF_8);
        assertThat(policyUnbounded.terminalWidth()).isEqualTo(0);
    }

    @Test
    void textRendererFormatsWithAccessibilityPolicy() {
        Document.TestDocument testDoc = new Document.TestDocument(
            List.of(), 5, 0, 0, List.of(), "✓ all tests passed"
        );
        Document doc = Document.of(
            Document.DocumentKind.TEST, "pytest", "PytestFilter", 0, true, Documents.provenance(true), testDoc
        );

        AccessibilityPolicy plainPolicy = AccessibilityPolicy.of(true);
        String plainRendered = TextRenderer.render(doc, plainPolicy);
        assertThat(plainRendered).contains("[OK] all tests passed");
        assertThat(plainRendered).doesNotContain("✓");

        String defaultRendered = TextRenderer.render(doc);
        assertThat(defaultRendered).contains("✓ all tests passed");
    }
}
