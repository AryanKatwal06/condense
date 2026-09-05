package com.condense.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Utf8LineDecoderTest {

    @Test
    void splitsOnNewlinesAndKeepsPartialUntilFinish() {
        List<String> lines = new ArrayList<>();
        Utf8LineDecoder decoder = new Utf8LineDecoder(lines::add);
        decoder.feed(bytes("hello\nwor"), 0, 9);
        assertThat(lines).containsExactly("hello");
        decoder.feed(bytes("ld\n"), 0, 3);
        assertThat(lines).containsExactly("hello", "world");
        decoder.finish();
        assertThat(lines).containsExactly("hello", "world");
    }

    @Test
    void crlfIsOneBreak() {
        List<String> lines = new ArrayList<>();
        Utf8LineDecoder decoder = new Utf8LineDecoder(lines::add);
        decoder.feed(bytes("a\r\nb\r\n"), 0, 6);
        decoder.finish();
        assertThat(lines).containsExactly("a", "b");
    }

    @Test
    void loneCrResetsTheCurrentLine() {
        List<String> lines = new ArrayList<>();
        Utf8LineDecoder decoder = new Utf8LineDecoder(lines::add);
        decoder.feed(bytes("progress\rfinal\n"), 0, 15);
        decoder.finish();
        assertThat(lines).containsExactly("final");
    }

    @Test
    void splitMultibyteUtf8IsHeldAcrossChunks() {
        List<String> lines = new ArrayList<>();
        Utf8LineDecoder decoder = new Utf8LineDecoder(lines::add);
        byte[] euro = "€\n".getBytes(StandardCharsets.UTF_8);
        assertThat(euro.length).isGreaterThan(2);
        decoder.feed(euro, 0, 1);
        assertThat(lines).isEmpty();
        decoder.feed(euro, 1, euro.length - 1);
        decoder.finish();
        assertThat(lines).containsExactly("€");
    }

    @Test
    void finishEmitsTrailingLineWithoutNewline() {
        List<String> lines = new ArrayList<>();
        Utf8LineDecoder decoder = new Utf8LineDecoder(lines::add);
        decoder.feed(bytes("tail"), 0, 4);
        decoder.finish();
        assertThat(lines).containsExactly("tail");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
