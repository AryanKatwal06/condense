package com.condense.read;

import com.condense.analytics.EstimatorInfo;
import com.condense.core.TokenCounter;
import com.condense.filter.strategy.JsonStructureStrategy;
import com.condense.trust.Provenance;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads a source file, applies a language-aware level, and stamps provenance.
 */
@ApplicationScoped
public class ReadService {

    public static final String EMPTY_STRIP_NOTICE = "condense[read]: no remaining source after comment-strip";
    public static final String UNKNOWN_LANG_HINT = "condense read: unknown language, using verbatim";

    private final LanguageDefinitionCatalog catalog;

    public ReadService() {
        this(LanguageDefinitionCatalog.standalone());
    }

    public ReadService(LanguageDefinitionCatalog catalog) {
        this.catalog = catalog == null ? LanguageDefinitionCatalog.standalone() : catalog;
    }

    public record Request(
        Path file,
        byte[] stdinBytes,
        boolean stdin,
        ReadLevel level,
        String languageOverride,
        Path cwd,
        Path rootOverride,
        Integer maxBytes
    ) {}

    public record Outcome(
        boolean ok,
        int exitCode,
        String stdout,
        String stderr,
        ReadReport report,
        int rawTokens,
        int outTokens
    ) {
        public static Outcome fail(String stderr) {
            return new Outcome(false, 1, "", stderr == null ? "" : stderr, null, 0, 0);
        }
    }

    public Outcome execute(Request request) {
        if (request == null) {
            return Outcome.fail("condense read: missing request");
        }
        ReadLevel level = request.level() == null ? ReadLevel.COMMENTS : request.level();
        ReadPathGate.GateResult gate;
        if (request.stdin()) {
            if (request.languageOverride() == null || request.languageOverride().isBlank()) {
                return Outcome.fail("condense read: --stdin requires --lang");
            }
            gate = ReadPathGate.openStdin(request.stdinBytes(), ReadPathGate.clampMaxBytes(request.maxBytes()));
        } else {
            gate = ReadPathGate.openFile(
                request.file(),
                request.cwd(),
                request.rootOverride(),
                ReadPathGate.clampMaxBytes(request.maxBytes())
            );
        }
        if (!gate.ok()) {
            return Outcome.fail("condense read: " + gate.error());
        }

        CompiledLanguage language = null;
        String stderr = "";
        if (request.languageOverride() != null && !request.languageOverride().isBlank()) {
            try {
                language = catalog.required(request.languageOverride());
            } catch (IllegalArgumentException e) {
                return Outcome.fail("condense read: " + e.getMessage());
            }
        } else {
            language = catalog.detect(request.file());
            if (language == null && level != ReadLevel.VERBATIM) {
                stderr = UNKNOWN_LANG_HINT;
                level = ReadLevel.VERBATIM;
            }
        }

        String source = new String(gate.bytes(), StandardCharsets.UTF_8);
        Rendered rendered = render(source, language, level);
        String stamped = level == ReadLevel.VERBATIM
            ? Provenance.passthrough(rendered.body())
            : Provenance.stampRead(rendered.body());
        int rawTokens = TokenCounter.count(source);
        int outTokens = TokenCounter.count(stamped);
        String path = request.stdin()
            ? "<stdin>"
            : (gate.canonicalFile() == null ? String.valueOf(request.file()) : gate.canonicalFile().toString());
        ReadReport report = new ReadReport(
            path,
            language == null ? "unknown" : language.name(),
            rendered.level().token(),
            language == null ? "unknown" : language.family().token(),
            gate.containedBy() == null ? null : gate.containedBy().toString(),
            countLines(source),
            countEmittedSourceLines(stamped, rendered.level()),
            gate.bytes().length,
            rawTokens,
            outTokens,
            EstimatorInfo.current(),
            rendered.fallback(),
            stamped
        );
        return new Outcome(true, 0, stamped, stderr, report, rawTokens, outTokens);
    }

    record Rendered(ReadLevel level, String body, String fallback) {}

    Rendered render(String source, CompiledLanguage language, ReadLevel level) {
        if (level == ReadLevel.VERBATIM || language == null) {
            return new Rendered(ReadLevel.VERBATIM, source == null ? "" : source, "none");
        }
        if (language.family() == LanguageFamily.DATA) {
            if (level == ReadLevel.OUTLINE) {
                String skeleton = JsonStructureStrategy.skeleton(source);
                return new Rendered(ReadLevel.OUTLINE, skeleton == null ? "" : skeleton, "none");
            }
            return new Rendered(ReadLevel.COMMENTS, source == null ? "" : source, "none");
        }
        SourceScanner.Classification classified = SourceScanner.classify(source, language);
        List<ReadRenderer.KeptLine> stripped = ReadRenderer.commentStrippedLines(classified);
        if (level == ReadLevel.COMMENTS) {
            if ((source != null && !source.isBlank()) && stripped.isEmpty()) {
                return new Rendered(ReadLevel.COMMENTS, EMPTY_STRIP_NOTICE, "notice");
            }
            return new Rendered(ReadLevel.COMMENTS, ReadRenderer.formatNumbered(neutralizeLines(stripped)), "none");
        }
        List<ReadRenderer.KeptLine> outlined = ReadRenderer.outlineLines(stripped, language);
        if (outlined.isEmpty() && !stripped.isEmpty()) {
            return new Rendered(ReadLevel.COMMENTS, ReadRenderer.formatNumbered(neutralizeLines(stripped)), "comments");
        }
        if (outlined.isEmpty() && source != null && !source.isBlank()) {
            return new Rendered(ReadLevel.OUTLINE, EMPTY_STRIP_NOTICE, "notice");
        }
        return new Rendered(ReadLevel.OUTLINE, ReadRenderer.formatNumbered(neutralizeLines(outlined)), "none");
    }

    private static List<ReadRenderer.KeptLine> neutralizeLines(List<ReadRenderer.KeptLine> lines) {
        return lines.stream()
            .map(line -> new ReadRenderer.KeptLine(
                line.originalNumber(),
                Provenance.isStampLine(line.text()) ? Provenance.QUOTED : line.text()))
            .toList();
    }

    private static int countLines(String source) {
        if (source == null || source.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                lines++;
            }
        }
        if (source.endsWith("\n") && lines > 0) {
            lines--;
        }
        return Math.max(lines, source.isBlank() ? 0 : 1);
    }

    private static int countEmittedSourceLines(String stamped, ReadLevel level) {
        if (stamped == null || stamped.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : stamped.split("\\R", -1)) {
            if (Provenance.isStampLine(line) || line.isEmpty()) {
                continue;
            }
            if (level != ReadLevel.VERBATIM && line.contains("| ")) {
                count++;
            } else if (level == ReadLevel.VERBATIM || level == ReadLevel.OUTLINE) {
                count++;
            }
        }
        return count;
    }
}
