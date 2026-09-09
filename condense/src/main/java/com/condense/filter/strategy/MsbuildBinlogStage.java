package com.condense.filter.strategy;

import com.condense.annotation.DeclarativeStage;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterIncident;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Bounded MSBuild binary-log reader. Unknown records are skipped by length.
 * Embedded zip archives are never unpacked.
 */
@DeclarativeStage(aliases = {"msbuild_binlog", "msbuild-binlog"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class MsbuildBinlogStage implements FilterStage {

    public static final MsbuildBinlogStage INSTANCE = new MsbuildBinlogStage();

    public static final int MIN_VERSION = 18;
    public static final int MAX_VERSION = 27;
    public static final int MAX_DECOMPRESSED = 32 * 1024 * 1024;
    public static final int MAX_RECORDS = 50_000;
    public static final int MAX_STRINGS = 100_000;
    public static final int MAX_STRING_CHARS = 1_048_576;
    public static final int MAX_FINDINGS = 500;
    public static final String CAPPED_LINE = "condense: msbuild_binlog capped";
    public static final String TOOL = "msbuild";

    static final int KIND_EOF = 0;
    static final int KIND_ERROR = 9;
    static final int KIND_WARNING = 10;
    static final int KIND_ARCHIVE = 17;
    static final int KIND_NAME_VALUE = 23;
    static final int KIND_STRING = 24;
    static final int STRING_START = 10;

    static final int FLAG_CONTEXT = 1 << 0;
    static final int FLAG_HELP = 1 << 1;
    static final int FLAG_MESSAGE = 1 << 2;
    static final int FLAG_SENDER = 1 << 3;
    static final int FLAG_THREAD = 1 << 4;
    static final int FLAG_TIMESTAMP = 1 << 5;
    static final int FLAG_SUBCATEGORY = 1 << 6;
    static final int FLAG_CODE = 1 << 7;
    static final int FLAG_FILE = 1 << 8;
    static final int FLAG_PROJECT = 1 << 9;
    static final int FLAG_LINE = 1 << 10;
    static final int FLAG_COLUMN = 1 << 11;
    static final int FLAG_END_LINE = 1 << 12;
    static final int FLAG_END_COLUMN = 1 << 13;
    static final int FLAG_ARGUMENTS = 1 << 14;
    static final int FLAG_IMPORTANCE = 1 << 15;
    static final int FLAG_EXTENDED = 1 << 16;

    private MsbuildBinlogStage() {}

    @Override
    public StageResult process(String input, FilterContext context) {
        String raw = input == null ? "" : input;
        List<Path> files = ArtifactFiles.find(context, ".binlog");
        if (files.isEmpty()) {
            return StageResult.continueWith(raw);
        }
        Parsed parsed = parseFiles(files, context);
        if (parsed == null || parsed.payload == null) {
            return StageResult.continueWith(raw);
        }
        if (context != null && context.documentBuilder() != null) {
            context.documentBuilder().diagnostic(parsed.payload);
        }
        String text = TextRenderer.renderDiagnostic(parsed.payload);
        if (parsed.capped) {
            text = text.isBlank() ? CAPPED_LINE : text + "\n" + CAPPED_LINE;
        }
        return StageResult.stopWith(text);
    }

    static Parsed parseFiles(List<Path> files, FilterContext context) {
        List<Document.Finding> findings = new ArrayList<>();
        boolean capped = false;
        boolean anySupported = false;
        for (Path file : files) {
            try {
                FileParse one = parseBytes(Files.readAllBytes(file), context);
                if (one == null) {
                    continue;
                }
                if (one.supported) {
                    anySupported = true;
                }
                capped |= one.capped;
                for (Document.Finding finding : one.findings) {
                    if (findings.size() >= MAX_FINDINGS) {
                        capped = true;
                        break;
                    }
                    findings.add(finding);
                }
            } catch (Exception ignored) {
                // fail-open to the next file or console
            }
        }
        if (!anySupported || findings.isEmpty()) {
            return null;
        }
        int errors = 0;
        int warnings = 0;
        for (Document.Finding finding : findings) {
            if ("warning".equalsIgnoreCase(finding.severity())) {
                warnings++;
            } else {
                errors++;
            }
        }
        Document.DiagnosticDocument payload = new Document.DiagnosticDocument(
            findings,
            errors,
            warnings,
            List.of(),
            Document.DiagnosticDocument.GROUP_COLON,
            false,
            TOOL);
        return new Parsed(payload, capped);
    }

    static FileParse parseBytes(byte[] bytes, FilterContext context) {
        if (bytes == null || bytes.length < 8) {
            return null;
        }
        int version = leInt(bytes, 0);
        int minReader = leInt(bytes, 4);
        if (version < MIN_VERSION) {
            incident(context, FilterIncident.KIND_BINLOG_VERSION, "binlog version " + version);
            return new FileParse(List.of(), false, false);
        }
        if (version > MAX_VERSION && (minReader < MIN_VERSION || minReader > MAX_VERSION)) {
            incident(context, FilterIncident.KIND_BINLOG_VERSION, "binlog version " + version);
            return new FileParse(List.of(), false, false);
        }
        byte[] decompressed;
        try {
            decompressed = gunzip(bytes, 8);
        } catch (Exception ignored) {
            return new FileParse(List.of(), true, false);
        }
        if (decompressed == null) {
            return new FileParse(List.of(), true, false);
        }
        return readRecords(decompressed, version);
    }

    private static FileParse readRecords(byte[] data, int version) {
        Cursor cursor = new Cursor(data);
        List<String> strings = new ArrayList<>();
        List<Document.Finding> findings = new ArrayList<>();
        boolean capped = false;
        int records = 0;
        while (cursor.remaining() > 0 && records < MAX_RECORDS) {
            records++;
            Integer kind = cursor.read7Bit();
            if (kind == null) {
                capped = true;
                break;
            }
            if (kind == KIND_EOF) {
                break;
            }
            if (kind == KIND_STRING) {
                String text = cursor.readCsharpString();
                if (text == null) {
                    capped = true;
                    break;
                }
                if (strings.size() >= MAX_STRINGS) {
                    capped = true;
                    break;
                }
                strings.add(text);
                continue;
            }
            if (kind == KIND_NAME_VALUE || kind == KIND_ARCHIVE || kind > KIND_STRING || kind == KIND_ERROR || kind == KIND_WARNING
                || (kind > KIND_EOF && kind != KIND_STRING)) {
                Integer length = cursor.read7Bit();
                if (length == null || length < 0 || length > cursor.remaining()) {
                    capped = true;
                    break;
                }
                int start = cursor.pos;
                if (kind == KIND_ERROR || kind == KIND_WARNING) {
                    Document.Finding finding = readFinding(cursor, strings, version, kind == KIND_WARNING);
                    if (finding != null && findings.size() < MAX_FINDINGS) {
                        findings.add(finding);
                    } else if (finding != null) {
                        capped = true;
                    }
                }
                cursor.pos = start + length;
                if (cursor.pos > cursor.data.length) {
                    capped = true;
                    break;
                }
            }
        }
        if (records >= MAX_RECORDS) {
            capped = true;
        }
        return new FileParse(findings, capped, true);
    }

    private static Document.Finding readFinding(Cursor cursor, List<String> strings, int version, boolean warning) {
        try {
            Integer flagsObj = cursor.read7Bit();
            if (flagsObj == null) {
                return null;
            }
            int flags = flagsObj;
            String message = null;
            String code = null;
            String file = null;
            Integer line = null;
            if ((flags & FLAG_MESSAGE) != 0) {
                message = readString(cursor, strings);
            }
            if ((flags & FLAG_CONTEXT) != 0) {
                skipContext(cursor, version);
            }
            if ((flags & FLAG_THREAD) != 0) {
                cursor.read7Bit();
            }
            if ((flags & FLAG_HELP) != 0) {
                readString(cursor, strings);
            }
            if ((flags & FLAG_SENDER) != 0) {
                readString(cursor, strings);
            }
            if ((flags & FLAG_TIMESTAMP) != 0) {
                cursor.skip(8);
                cursor.read7Bit();
            }
            if ((flags & FLAG_EXTENDED) != 0) {
                readString(cursor, strings);
                Integer count = cursor.read7Bit();
                if (count != null) {
                    for (int i = 0; i < count; i++) {
                        readString(cursor, strings);
                        readString(cursor, strings);
                    }
                }
                readString(cursor, strings);
            }
            if ((flags & FLAG_SUBCATEGORY) != 0) {
                readString(cursor, strings);
            }
            if ((flags & FLAG_CODE) != 0) {
                code = readString(cursor, strings);
            }
            if ((flags & FLAG_FILE) != 0) {
                file = readString(cursor, strings);
            }
            if ((flags & FLAG_PROJECT) != 0) {
                readString(cursor, strings);
            }
            if ((flags & FLAG_LINE) != 0) {
                Integer value = cursor.read7Bit();
                line = value;
            }
            if ((flags & FLAG_COLUMN) != 0) {
                cursor.read7Bit();
            }
            if ((flags & FLAG_END_LINE) != 0) {
                cursor.read7Bit();
            }
            if ((flags & FLAG_END_COLUMN) != 0) {
                cursor.read7Bit();
            }
            if ((flags & FLAG_ARGUMENTS) != 0) {
                Integer count = cursor.read7Bit();
                if (count != null) {
                    for (int i = 0; i < count; i++) {
                        readString(cursor, strings);
                    }
                }
            }
            if (version >= 13 && (flags & FLAG_IMPORTANCE) != 0) {
                cursor.read7Bit();
            }
            if (message == null || message.isBlank()) {
                return null;
            }
            String display = code == null || code.isBlank() ? message : code + ": " + message;
            return new Document.Finding(
                file == null ? "" : file,
                line,
                code == null ? "" : code,
                display,
                warning ? "warning" : "error");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void skipContext(Cursor cursor, int version) {
        for (int i = 0; i < 6; i++) {
            cursor.read7Bit();
        }
        if (version > 1) {
            cursor.read7Bit();
        }
    }

    private static String readString(Cursor cursor, List<String> strings) {
        Integer index = cursor.read7Bit();
        if (index == null || index == 0) {
            return null;
        }
        if (index == 1) {
            return "";
        }
        int slot = index - STRING_START;
        if (slot < 0 || slot >= strings.size()) {
            return null;
        }
        return strings.get(slot);
    }

    private static byte[] gunzip(byte[] bytes, int offset) throws IOException {
        try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes, offset, bytes.length - offset))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = gzip.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_DECOMPRESSED) {
                    return null;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static int leInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void incident(FilterContext context, String kind, String detail) {
        if (context == null) {
            return;
        }
        context.recordIncident(new FilterIncident(kind, null, "msbuild_binlog", true, detail));
    }

    static final class Parsed {
        final Document.DiagnosticDocument payload;
        final boolean capped;

        Parsed(Document.DiagnosticDocument payload, boolean capped) {
            this.payload = payload;
            this.capped = capped;
        }
    }

    static final class FileParse {
        final List<Document.Finding> findings;
        final boolean capped;
        final boolean supported;

        FileParse(List<Document.Finding> findings, boolean capped, boolean supported) {
            this.findings = findings;
            this.capped = capped;
            this.supported = supported;
        }
    }

    static final class Cursor {
        final byte[] data;
        int pos;

        Cursor(byte[] data) {
            this.data = data == null ? new byte[0] : data;
        }

        int remaining() {
            return data.length - pos;
        }

        Integer read7Bit() {
            int value = 0;
            int shift = 0;
            for (int i = 0; i < 5; i++) {
                if (pos >= data.length) {
                    return null;
                }
                int b = data[pos++] & 0xff;
                value |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
            return null;
        }

        String readCsharpString() {
            Integer length = read7Bit();
            if (length == null || length < 0 || length > remaining() || length > MAX_STRING_CHARS) {
                return null;
            }
            String text = new String(data, pos, length, StandardCharsets.UTF_8);
            pos += length;
            return text;
        }

        void skip(int bytes) {
            pos = Math.min(data.length, pos + bytes);
        }
    }
}
