package com.condense.filter.strategy;

import com.condense.annotation.DeclarativeStage;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterIncident;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Namespace-aware TRX reader. DTDs and external entities are disabled.
 */
@DeclarativeStage(aliases = {"trx_report", "trx-report"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class TrxReportStage implements FilterStage {

    public static final TrxReportStage INSTANCE = new TrxReportStage();
    public static final String NS = "http://microsoft.com/schemas/VisualStudio/TeamTest/2010";
    public static final int MAX_FILES = 8;
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_RESULTS = 2_000;
    public static final int MAX_FAILED = 50;
    public static final int MAX_STACK = 8 * 1024;
    public static final String TOOL = "trx";

    private TrxReportStage() {}

    @Override
    public StageResult process(String input, FilterContext context) {
        String raw = input == null ? "" : input;
        List<Path> files = ArtifactFiles.find(context, ".trx");
        if (files.isEmpty()) {
            return StageResult.continueWith(raw);
        }
        Parsed parsed = parseFiles(files, context);
        if (parsed == null || parsed.payload == null) {
            return StageResult.continueWith(raw);
        }
        if (context != null && context.documentBuilder() != null) {
            context.documentBuilder().test(parsed.payload);
        }
        return StageResult.stopWith(TextRenderer.renderTest(parsed.payload));
    }

    static Parsed parseFiles(List<Path> files, FilterContext context) {
        Merge merge = new Merge();
        int used = 0;
        for (Path file : files) {
            if (used >= MAX_FILES) {
                break;
            }
            try {
                if (Files.size(file) > MAX_FILE_BYTES) {
                    continue;
                }
            } catch (Exception ignored) {
                continue;
            }
            used++;
            try {
                parseOne(file, merge, context);
            } catch (Exception e) {
                if (looksLikeXxe(e)) {
                    incident(context, "entity expansion blocked");
                    if (!merge.hasSignal()) {
                        return null;
                    }
                }
            }
        }
        if (!merge.hasSignal()) {
            return null;
        }
        return new Parsed(merge.toPayload());
    }

    private static void parseOne(Path file, Merge merge, FilterContext context) throws Exception {
        if (declaresDtdOrEntity(file)) {
            incident(context, "dtd or entity");
            throw new XMLStreamException("xxe");
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            int depth = 0;
            int scanned = 0;
            String resultName = null;
            String resultOutcome = null;
            String resultDuration = null;
            String message = null;
            String stack = null;
            boolean inError = false;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                        incident(context, "dtd or entity");
                        throw new XMLStreamException("xxe");
                    }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        if (depth > MAX_DEPTH) {
                            flushFailure(merge, resultName, resultOutcome, message, stack, resultDuration);
                            return;
                        }
                        String local = reader.getLocalName();
                        if ("Counters".equals(local)) {
                            merge.passed += intAttr(reader, "passed");
                            merge.failed += intAttr(reader, "failed");
                            merge.errors += intAttr(reader, "error");
                            merge.skipped += intAttr(reader, "notExecuted") + intAttr(reader, "inconclusive");
                            merge.total += intAttr(reader, "total");
                            merge.sawCounters = true;
                        } else if ("UnitTestResult".equals(local)) {
                            scanned++;
                            if (scanned > MAX_RESULTS) {
                                flushFailure(merge, resultName, resultOutcome, message, stack, resultDuration);
                                return;
                            }
                            resultName = attr(reader, "testName");
                            resultOutcome = attr(reader, "outcome");
                            resultDuration = attr(reader, "duration");
                            message = null;
                            stack = null;
                        } else if ("ErrorInfo".equals(local)) {
                            inError = true;
                        } else if (inError && "Message".equals(local)) {
                            message = reader.getElementText();
                            depth--;
                        } else if (inError && "StackTrace".equals(local)) {
                            stack = clip(reader.getElementText(), MAX_STACK);
                            depth--;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String local = reader.getLocalName();
                        if ("ErrorInfo".equals(local)) {
                            inError = false;
                        } else if ("UnitTestResult".equals(local)) {
                            flushFailure(merge, resultName, resultOutcome, message, stack, resultDuration);
                            resultName = null;
                        }
                        depth--;
                    }
                }
                flushFailure(merge, resultName, resultOutcome, message, stack, resultDuration);
            } catch (Exception e) {
                flushFailure(merge, resultName, resultOutcome, message, stack, resultDuration);
                throw e;
            } finally {
                reader.close();
            }
        }
    }

    private static void flushFailure(
            Merge merge,
            String name,
            String outcome,
            String message,
            String stack,
            String duration) {
        if (name == null || name.isBlank()) {
            return;
        }
        String normalized = outcome == null ? "" : outcome.toLowerCase(Locale.ROOT);
        if ("failed".equals(normalized) || "error".equals(normalized)) {
            merge.addFailure(name, normalized, message, stack, duration);
        }
    }

    private static boolean declaresDtdOrEntity(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] prefix = new byte[8192];
            int read = in.read(prefix);
            if (read <= 0) {
                return false;
            }
            String head = new String(prefix, 0, read, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return head.contains("<!doctype") || head.contains("<!entity");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean looksLikeXxe(Exception e) {
        String text = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return text.contains("xxe") || text.contains("entity") || text.contains("dtd") || text.contains("doctype");
    }

    private static void incident(FilterContext context, String detail) {
        if (context != null) {
            context.recordIncident(new FilterIncident(
                FilterIncident.KIND_TRX_XXE, null, "trx_report", true, detail));
        }
    }

    private static String attr(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    private static int intAttr(XMLStreamReader reader, String name) {
        String value = attr(reader, name);
        if (value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static final class Merge {
        private int passed;
        private int failed;
        private int errors;
        private int skipped;
        private int total;
        private boolean sawCounters;
        private final List<Document.TestCase> cases = new ArrayList<>();
        private final List<String> lines = new ArrayList<>();

        void addFailure(String name, String outcome, String message, String stack, String duration) {
            if (cases.size() >= MAX_FAILED) {
                return;
            }
            String label = name == null || name.isBlank() ? "unknown" : name;
            String detail = "Failed " + shortName(label);
            if (message != null && !message.isBlank()) {
                detail = detail + " " + message.strip();
            }
            Integer ms = millis(duration);
            cases.add(new Document.TestCase(label, outcome, detail, null, ms, blankToNull(stack)));
            lines.add(detail);
        }

        boolean hasSignal() {
            return sawCounters || !cases.isEmpty();
        }

        Document.TestDocument toPayload() {
            if (total <= 0) {
                total = passed + failed + errors + skipped;
            }
            return new Document.TestDocument(
                cases, passed, failed, errors, lines, "", skipped, total, TOOL);
        }
    }

    private static String shortName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static Integer millis(String duration) {
        if (duration == null || duration.isBlank()) {
            return null;
        }
        try {
            String[] parts = duration.split(":");
            if (parts.length != 3) {
                return null;
            }
            double seconds = Double.parseDouble(parts[2]);
            int minutes = Integer.parseInt(parts[1]);
            int hours = Integer.parseInt(parts[0]);
            return (int) Math.round(((hours * 60 + minutes) * 60 + seconds) * 1000);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static final class Parsed {
        final Document.TestDocument payload;

        Parsed(Document.TestDocument payload) {
            this.payload = payload;
        }
    }
}
