package com.condense.read;

/**
 * Hand-written source scanner. Comment starters fire only in code state.
 * When a construct is ambiguous the bytes stay KEEP.
 */
public final class SourceScanner {

    public enum Mark {
        KEEP,
        COMMENT
    }

    public record Classification(String source, int[] codePoints, Mark[] marks) {
        public Classification {
            if (codePoints == null) {
                codePoints = source == null ? new int[0] : source.codePoints().toArray();
            }
            if (marks == null) {
                marks = new Mark[codePoints.length];
                java.util.Arrays.fill(marks, Mark.KEEP);
            }
        }

        public String keepText() {
            StringBuilder out = new StringBuilder(codePoints.length);
            for (int i = 0; i < codePoints.length; i++) {
                if (marks[i] == Mark.KEEP) {
                    out.appendCodePoint(codePoints[i]);
                }
            }
            return out.toString();
        }

        public String nonWhitespaceKeep() {
            StringBuilder out = new StringBuilder(codePoints.length);
            for (int i = 0; i < codePoints.length; i++) {
                if (marks[i] == Mark.KEEP && !isWhitespace(codePoints[i])) {
                    out.appendCodePoint(codePoints[i]);
                }
            }
            return out.toString();
        }

        public String nonWhitespaceComment() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < codePoints.length; i++) {
                if (marks[i] == Mark.COMMENT && !isWhitespace(codePoints[i])) {
                    out.appendCodePoint(codePoints[i]);
                }
            }
            return out.toString();
        }
    }

    private enum State {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING
    }

    private SourceScanner() {}

    public static Classification classify(String source, CompiledLanguage language) {
        String text = source == null ? "" : source;
        int[] cps = text.codePoints().toArray();
        Mark[] marks = new Mark[cps.length];
        java.util.Arrays.fill(marks, Mark.KEEP);
        if (language == null || language.family() == LanguageFamily.DATA || cps.length == 0) {
            return new Classification(text, cps, marks);
        }
        scan(cps, marks, language);
        return new Classification(text, cps, marks);
    }

    private static void scan(int[] cps, Mark[] marks, CompiledLanguage language) {
        LanguageDefinition def = language.definition();
        State state = State.CODE;
        int blockDepth = 0;
        String stringEnd = null;
        boolean stringRaw = false;
        String stringEscape = "\\";
        int i = 0;
        while (i < cps.length) {
            switch (state) {
                case CODE -> {
                    RawOpen raw = tryOpenRaw(cps, i, language.rawStyle());
                    if (raw.consumed() > 0) {
                        if (raw.enterString()) {
                            stringEnd = raw.end();
                            stringRaw = raw.raw();
                            stringEscape = raw.escape();
                            state = State.STRING;
                        }
                        i += raw.consumed();
                        continue;
                    }
                    LanguageDefinition.StringDef opened = openedString(cps, i, def);
                    if (opened != null) {
                        stringEnd = opened.delimiter();
                        stringRaw = opened.rawString();
                        stringEscape = opened.escape() == null || opened.escape().isEmpty()
                            ? "\\" : opened.escape();
                        state = State.STRING;
                        i += opened.delimiter().length();
                        continue;
                    }
                    if (language.family() == LanguageFamily.XML || language.family() == LanguageFamily.MARKDOWN) {
                        if (match(cps, i, "<!--")) {
                            markRange(marks, i, 4, Mark.COMMENT);
                            stringEnd = "-->";
                            state = State.BLOCK_COMMENT;
                            blockDepth = 1;
                            i += 4;
                            continue;
                        }
                    } else if (language.family() == LanguageFamily.POWERSHELL) {
                        if (match(cps, i, "<#")) {
                            markRange(marks, i, 2, Mark.COMMENT);
                            stringEnd = "#>";
                            state = State.BLOCK_COMMENT;
                            blockDepth = 1;
                            i += 2;
                            continue;
                        }
                    }
                    String blockStart = effectiveBlockStart(language, def);
                    String blockEnd = effectiveBlockEnd(language, def);
                    if (blockStart != null && match(cps, i, blockStart)) {
                        markRange(marks, i, blockStart.length(), Mark.COMMENT);
                        stringEnd = blockEnd;
                        state = State.BLOCK_COMMENT;
                        blockDepth = 1;
                        i += blockStart.length();
                        continue;
                    }
                    String line = effectiveLineComment(language, def);
                    if (line != null && match(cps, i, line)) {
                        markRange(marks, i, line.length(), Mark.COMMENT);
                        state = State.LINE_COMMENT;
                        i += line.length();
                        continue;
                    }
                    i++;
                }
                case LINE_COMMENT -> {
                    marks[i] = Mark.COMMENT;
                    if (cps[i] == '\n') {
                        marks[i] = Mark.KEEP;
                        state = State.CODE;
                    }
                    i++;
                }
                case BLOCK_COMMENT -> {
                    String end = stringEnd != null ? stringEnd : effectiveBlockEnd(language, def);
                    String start = effectiveBlockStart(language, def);
                    if (def.allowsNestedBlockComments() && start != null && match(cps, i, start)) {
                        markRange(marks, i, start.length(), Mark.COMMENT);
                        blockDepth++;
                        i += start.length();
                        continue;
                    }
                    if (end != null && match(cps, i, end)) {
                        markRange(marks, i, end.length(), Mark.COMMENT);
                        i += end.length();
                        blockDepth--;
                        if (blockDepth <= 0) {
                            state = State.CODE;
                            stringEnd = null;
                            blockDepth = 0;
                        }
                        continue;
                    }
                    marks[i] = Mark.COMMENT;
                    i++;
                }
                case STRING -> {
                    if (stringEnd != null && match(cps, i, stringEnd)) {
                        int endLen = stringEnd.length();
                        boolean doubled = !stringRaw
                            && stringEscape != null
                            && stringEscape.equals(stringEnd)
                            && match(cps, i + endLen, stringEnd);
                        if (doubled) {
                            i += endLen * 2;
                            continue;
                        }
                        i += endLen;
                        state = State.CODE;
                        stringEnd = null;
                        continue;
                    }
                    if (!stringRaw && stringEscape != null && !stringEscape.equals(stringEnd)
                        && match(cps, i, stringEscape)) {
                        i += stringEscape.length();
                        if (i < cps.length) {
                            i++;
                        }
                        continue;
                    }
                    i++;
                }
            }
        }
    }

    private static String effectiveLineComment(CompiledLanguage language, LanguageDefinition def) {
        return switch (language.family()) {
            case DATA -> null;
            case XML, MARKDOWN, CSS -> null;
            case POWERSHELL -> "#";
            case SQL -> def.lineComment() != null ? def.lineComment() : "--";
            default -> def.lineComment();
        };
    }

    private static String effectiveBlockStart(CompiledLanguage language, LanguageDefinition def) {
        return switch (language.family()) {
            case DATA -> null;
            case XML, MARKDOWN -> "<!--";
            case POWERSHELL -> "<#";
            case CSS, SQL, C_LIKE, HASH -> def.blockCommentStart();
        };
    }

    private static String effectiveBlockEnd(CompiledLanguage language, LanguageDefinition def) {
        return switch (language.family()) {
            case DATA -> null;
            case XML, MARKDOWN -> "-->";
            case POWERSHELL -> "#>";
            case CSS, SQL, C_LIKE, HASH -> def.blockCommentEnd();
        };
    }

    private static LanguageDefinition.StringDef openedString(int[] cps, int i, LanguageDefinition def) {
        LanguageDefinition.StringDef best = null;
        for (LanguageDefinition.StringDef spec : def.strings()) {
            if (spec.delimiter() == null || spec.delimiter().isEmpty()) {
                continue;
            }
            if (match(cps, i, spec.delimiter())) {
                if (best == null || spec.delimiter().length() > best.delimiter().length()) {
                    best = spec;
                }
            }
        }
        return best;
    }

    private record RawOpen(int consumed, boolean enterString, String end, boolean raw, String escape) {
        static RawOpen none() {
            return new RawOpen(0, false, null, false, "\\");
        }

        static RawOpen span(int consumed) {
            return new RawOpen(consumed, false, null, true, "\\");
        }

        static RawOpen into(int openerLength, String end, boolean raw) {
            return new RawOpen(openerLength, true, end, raw, "\\");
        }
    }

    private static RawOpen tryOpenRaw(int[] cps, int i, RawStringStyle style) {
        return switch (style) {
            case NONE -> RawOpen.none();
            case RUST -> {
                int n = openRustRaw(cps, i);
                yield n > 0 ? RawOpen.span(n) : RawOpen.none();
            }
            case CPP -> {
                int n = openCppRaw(cps, i);
                yield n > 0 ? RawOpen.span(n) : RawOpen.none();
            }
            case PYTHON -> {
                int n = openPythonRaw(cps, i);
                yield n > 0 ? RawOpen.span(n) : RawOpen.none();
            }
            case JAVA_TEXT -> match(cps, i, "\"\"\"")
                ? RawOpen.into(3, "\"\"\"", false)
                : RawOpen.none();
            case JS_TEMPLATE -> cps[i] == '`'
                ? RawOpen.into(1, "`", false)
                : RawOpen.none();
            case GO_RAW -> cps[i] == '`'
                ? RawOpen.into(1, "`", true)
                : RawOpen.none();
        };
    }

    /**
     * Consumes an entire raw string in one pass (marks stay KEEP) and returns
     * the number of code points consumed, including delimiters. Returns 0 if
     * this is not a raw-string start so the caller can try ordinary strings.
     */
    private static int openRustRaw(int[] cps, int i) {
        int pos = i;
        if (pos < cps.length && (cps[pos] == 'b' || cps[pos] == 'c' || cps[pos] == 'B' || cps[pos] == 'C')) {
            pos++;
        }
        if (pos >= cps.length || (cps[pos] != 'r' && cps[pos] != 'R')) {
            return 0;
        }
        pos++;
        int hashes = 0;
        while (pos < cps.length && cps[pos] == '#') {
            hashes++;
            pos++;
        }
        if (pos >= cps.length || cps[pos] != '"') {
            return 0;
        }
        pos++;
        while (pos < cps.length) {
            if (cps[pos] == '"') {
                int after = pos + 1;
                int seen = 0;
                while (seen < hashes && after < cps.length && cps[after] == '#') {
                    seen++;
                    after++;
                }
                if (seen == hashes) {
                    return after - i;
                }
            }
            pos++;
        }
        return cps.length - i;
    }

    private static int openCppRaw(int[] cps, int i) {
        int pos = i;
        if (match(cps, pos, "u8")) {
            pos += 2;
        } else if (pos < cps.length && (cps[pos] == 'L' || cps[pos] == 'u' || cps[pos] == 'U')) {
            pos++;
        }
        if (pos >= cps.length || (cps[pos] != 'R')) {
            return 0;
        }
        pos++;
        if (pos >= cps.length || cps[pos] != '"') {
            return 0;
        }
        pos++;
        StringBuilder delim = new StringBuilder();
        while (pos < cps.length && cps[pos] != '(' && cps[pos] != '\n' && delim.length() < 16) {
            delim.appendCodePoint(cps[pos]);
            pos++;
        }
        if (pos >= cps.length || cps[pos] != '(') {
            return 0;
        }
        pos++;
        int[] closer = (")" + delim + "\"").codePoints().toArray();
        while (pos < cps.length) {
            if (matchAt(cps, pos, closer)) {
                return pos + closer.length - i;
            }
            pos++;
        }
        return cps.length - i;
    }

    private static int openPythonRaw(int[] cps, int i) {
        int pos = i;
        int prefixes = 0;
        while (pos < cps.length && prefixes < 3 && isPythonPrefix(cps[pos])) {
            pos++;
            prefixes++;
        }
        String delim;
        if (match(cps, pos, "\"\"\"")) {
            delim = "\"\"\"";
        } else if (match(cps, pos, "'''")) {
            delim = "'''";
        } else if (prefixes > 0 && pos < cps.length && (cps[pos] == '"' || cps[pos] == '\'')) {
            delim = Character.toString(cps[pos]);
        } else {
            return 0;
        }
        pos += delim.length();
        boolean raw = prefixes > 0;
        while (pos < cps.length) {
            if (!raw && cps[pos] == '\\' && pos + 1 < cps.length) {
                pos += 2;
                continue;
            }
            if (match(cps, pos, delim)) {
                return pos + delim.length() - i;
            }
            pos++;
        }
        return cps.length - i;
    }

    private static boolean isPythonPrefix(int cp) {
        return cp == 'r' || cp == 'R' || cp == 'f' || cp == 'F' || cp == 'b' || cp == 'B'
            || cp == 'u' || cp == 'U';
    }

    private static boolean match(int[] cps, int i, String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        return matchAt(cps, i, token.codePoints().toArray());
    }

    private static boolean matchAt(int[] cps, int i, int[] token) {
        if (i < 0 || token.length == 0 || i + token.length > cps.length) {
            return false;
        }
        for (int k = 0; k < token.length; k++) {
            if (cps[i + k] != token[k]) {
                return false;
            }
        }
        return true;
    }

    private static void markRange(Mark[] marks, int start, int length, Mark mark) {
        int end = Math.min(marks.length, start + length);
        for (int i = start; i < end; i++) {
            marks[i] = mark;
        }
    }

    static boolean isWhitespace(int cp) {
        return cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r' || cp == '\f'
            || Character.isWhitespace(cp);
    }
}
