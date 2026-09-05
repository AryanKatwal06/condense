package com.condense.core;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Incremental UTF-8 line splitter. Holds incomplete code points across chunks,
 * treats {@code \r\n} as one break, and treats a lone {@code \r} as a progress-bar
 * reset of the current line rather than an emission.
 */
public final class Utf8LineDecoder {

    private final Consumer<String> onLine;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private ByteBuffer leftover = ByteBuffer.allocate(0);
    private final StringBuilder line = new StringBuilder();
    private boolean pendingCr;

    public Utf8LineDecoder(Consumer<String> onLine) {
        this.onLine = Objects.requireNonNull(onLine, "onLine");
    }

    public synchronized void feed(byte[] chunk, int offset, int length) {
        if (chunk == null || length <= 0) {
            return;
        }
        ByteBuffer incoming = prependLeftover(chunk, offset, length);
        CharBuffer chars = CharBuffer.allocate(incoming.remaining() + 8);
        decoder.decode(incoming, chars, false);
        leftover = sliceRemaining(incoming);
        chars.flip();
        consume(chars, false);
    }

    public synchronized void finish() {
        ByteBuffer incoming = leftover.hasRemaining() ? leftover : ByteBuffer.allocate(0);
        CharBuffer chars = CharBuffer.allocate(incoming.remaining() + 8);
        decoder.decode(incoming, chars, true);
        decoder.flush(chars);
        leftover = ByteBuffer.allocate(0);
        chars.flip();
        consume(chars, true);
        if (pendingCr) {
            pendingCr = false;
            line.setLength(0);
        }
        if (line.length() > 0) {
            emit();
        }
        decoder.reset();
    }

    private void consume(CharBuffer chars, boolean end) {
        while (chars.hasRemaining()) {
            char c = chars.get();
            if (pendingCr) {
                pendingCr = false;
                if (c == '\n') {
                    emit();
                    continue;
                }
                line.setLength(0);
            }
            if (c == '\r') {
                if (!end && !chars.hasRemaining()) {
                    pendingCr = true;
                    continue;
                }
                if (chars.hasRemaining() && chars.get(chars.position()) == '\n') {
                    chars.get();
                    emit();
                    continue;
                }
                line.setLength(0);
                continue;
            }
            if (c == '\n') {
                emit();
                continue;
            }
            line.append(c);
        }
    }

    private void emit() {
        onLine.accept(line.toString());
        line.setLength(0);
    }

    private ByteBuffer prependLeftover(byte[] chunk, int offset, int length) {
        if (!leftover.hasRemaining()) {
            return ByteBuffer.wrap(chunk, offset, length);
        }
        ByteBuffer combined = ByteBuffer.allocate(leftover.remaining() + length);
        combined.put(leftover);
        combined.put(chunk, offset, length);
        combined.flip();
        leftover = ByteBuffer.allocate(0);
        return combined;
    }

    private static ByteBuffer sliceRemaining(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return ByteBuffer.allocate(0);
        }
        byte[] rest = new byte[buffer.remaining()];
        buffer.get(rest);
        return ByteBuffer.wrap(rest);
    }
}
