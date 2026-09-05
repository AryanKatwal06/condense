package com.condense.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class Utf8LineDecoderBoundTest {

    @Test
    void oversizedLineEmitsTruncatedWithoutThrowing() {
        List<String> lines = new ArrayList<>();
        Utf8LineDecoder decoder = new Utf8LineDecoder(lines::add);
        byte[] chunk = new byte[Utf8LineDecoder.MAX_LINE_CHARS + 1];
        Arrays.fill(chunk, (byte) 'A');
        assertThatCode(() -> {
            decoder.feed(chunk, 0, chunk.length);
            decoder.finish();
        }).doesNotThrowAnyException();
        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0).length()).isEqualTo(Utf8LineDecoder.MAX_LINE_CHARS);
        assertThat(lines.stream().mapToInt(String::length).sum())
            .isEqualTo(Utf8LineDecoder.MAX_LINE_CHARS + 1);
        assertThat(lines.get(lines.size() - 1)).isEqualTo("A");
    }
}
