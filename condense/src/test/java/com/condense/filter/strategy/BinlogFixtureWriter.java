package com.condense.filter.strategy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Test-only v18 binlog writer. Production code never writes binlogs.
 */
public final class BinlogFixtureWriter {

    private BinlogFixtureWriter() {}

    public static byte[] typicalErrorAndWarning() {
        return write(18, 18, List.of(
            new Event(MsbuildBinlogStage.KIND_ERROR, "CS0029", "Program.cs", 12,
                "Cannot implicitly convert type 'string' to 'int'"),
            new Event(MsbuildBinlogStage.KIND_WARNING, "CS0219", "Program.cs", 8,
                "The variable 'unused' is assigned but its value is never used")
        ), List.of());
    }

    public static byte[] withUnknownRecord() {
        return write(18, 18, List.of(
            new Event(MsbuildBinlogStage.KIND_ERROR, "CS0117", "Billing.cs", 44,
                "'Invoice' does not contain a definition for 'Save'")
        ), List.of(new byte[] {42, 3, 1, 2, 3}));
    }

    public static byte[] version(int fileVersion, int minReader, List<Event> events) {
        return write(fileVersion, minReader, events, List.of());
    }

    public static Path writeTypical(Path file) throws IOException {
        Files.write(file, typicalErrorAndWarning());
        return file;
    }

    public static byte[] write(
            int fileVersion,
            int minReader,
            List<Event> events,
            List<byte[]> extraRecords
    ) {
        try {
            ByteArrayOutputStream gzipBody = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBody)) {
                List<String> strings = new ArrayList<>();
                for (Event event : events) {
                    emitString(gzip, strings, event.code);
                    emitString(gzip, strings, event.file);
                    emitString(gzip, strings, event.message);
                    byte[] payload = eventPayload(strings, event);
                    write7(gzip, event.kind);
                    write7(gzip, payload.length);
                    gzip.write(payload);
                }
                for (byte[] extra : extraRecords) {
                    gzip.write(extra);
                }
                write7(gzip, MsbuildBinlogStage.KIND_EOF);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(le(fileVersion));
            out.write(le(minReader));
            out.write(gzipBody.toByteArray());
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Event(int kind, String code, String file, int line, String message) {}

    private static void emitString(GZIPOutputStream gzip, List<String> strings, String text) throws IOException {
        if (text == null || text.isEmpty() || strings.contains(text)) {
            return;
        }
        strings.add(text);
        write7(gzip, MsbuildBinlogStage.KIND_STRING);
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        write7(gzip, utf8.length);
        gzip.write(utf8);
    }

    private static byte[] eventPayload(List<String> strings, Event event) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        int flags = MsbuildBinlogStage.FLAG_MESSAGE
            | MsbuildBinlogStage.FLAG_CODE
            | MsbuildBinlogStage.FLAG_FILE
            | MsbuildBinlogStage.FLAG_LINE;
        write7(payload, flags);
        write7(payload, index(strings, event.message));
        write7(payload, index(strings, event.code));
        write7(payload, index(strings, event.file));
        write7(payload, event.line);
        return payload.toByteArray();
    }

    private static int index(List<String> strings, String text) {
        if (text == null) {
            return 0;
        }
        if (text.isEmpty()) {
            return 1;
        }
        return MsbuildBinlogStage.STRING_START + strings.indexOf(text);
    }

    static void write7(java.io.OutputStream out, int value) throws IOException {
        int current = value;
        while ((current & ~0x7f) != 0) {
            out.write((current & 0x7f) | 0x80);
            current >>>= 7;
        }
        out.write(current & 0x7f);
    }

    private static byte[] le(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}
