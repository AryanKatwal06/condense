package com.condense.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorEncodingTest {

    private final TokenEstimator estimator = Utf8WeightedTokenEstimator.INSTANCE;

    @TempDir
    Path tempDir;

    @Test
    void nullAndEmptyReturnZero() {
        assertThat(estimator.count((String) null)).isZero();
        assertThat(estimator.count("")).isZero();
        assertThat(estimator.count((Path) null)).isZero();
    }

    @Test
    void cjkFileAndStringAgreeAndAreNotUtf16LengthOverFour() throws Exception {
        String cjk = "你好世界";
        Path file = tempDir.resolve("cjk.txt");
        Files.writeString(file, cjk, StandardCharsets.UTF_8);

        int fromString = estimator.count(cjk);
        int fromFile = estimator.count(file);

        assertThat(fromString).isEqualTo(fromFile);
        assertThat(fromString).isEqualTo(4);
        assertThat(fromString)
            .as("old String.length()/4 would have reported %d", (cjk.length() + 3) / 4)
            .isNotEqualTo((cjk.length() + 3) / 4);
        assertThat(fromFile)
            .as("old Files.size()/4 would have reported %d", (Files.size(file) + 3) / 4)
            .isNotEqualTo((int) ((Files.size(file) + 3) / 4));
    }

    @Test
    void surrogatePairEmojiIsOneCodePointNotTwoUtf16Units() {
        String emoji = "😀😀😀😀";
        int counted = estimator.count(emoji);
        assertThat(counted).isEqualTo(4);
        assertThat(counted)
            .as("old text.length()/4 treats each emoji as two UTF-16 units")
            .isNotEqualTo((emoji.length() + 3) / 4);
        assertThat(emoji.length()).isEqualTo(8);
    }

    @Test
    void latinCeilingDivisionStillHoldsForAscii() {
        assertThat(estimator.count("a")).isEqualTo(1);
        assertThat(estimator.count("abcd")).isEqualTo(1);
        assertThat(estimator.count("abcde")).isEqualTo(2);
        assertThat(estimator.count("a".repeat(400))).isEqualTo(100);
    }

    @Test
    void malformedUtf8FileDoesNotThrowAndDecodesWithReplacement() throws Exception {
        Path file = tempDir.resolve("bad.bin");
        Files.write(file, new byte[] {(byte) 0xFF, (byte) 0xFE, 0x41});
        assertThat(estimator.count(file)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void missingFileReturnsZero() {
        assertThat(estimator.count(tempDir.resolve("does-not-exist.txt"))).isZero();
    }

    @Test
    void tokenCounterFacadeAgreesWithEstimator() throws Exception {
        String text = "hello 世界";
        Path file = tempDir.resolve("mixed.txt");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        assertThat(TokenCounter.count(text)).isEqualTo(estimator.count(text));
        assertThat(TokenCounter.count(file)).isEqualTo(estimator.count(file));
        assertThat(TokenCounter.count(file)).isEqualTo(TokenCounter.count(text));
    }
}
