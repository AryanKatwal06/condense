package com.condense.read;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a scanner classification into kept source lines numbered from the original file.
 */
public final class ReadRenderer {

    public record KeptLine(int originalNumber, String text) {}

    private ReadRenderer() {}

    public static List<KeptLine> commentStrippedLines(SourceScanner.Classification classification) {
        List<KeptLine> lines = new ArrayList<>();
        if (classification == null || classification.codePoints().length == 0) {
            return lines;
        }
        int[] cps = classification.codePoints();
        SourceScanner.Mark[] marks = classification.marks();
        int lineNo = 1;
        StringBuilder keep = new StringBuilder();
        boolean sawNonWs = false;
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            if (cp == '\r') {
                continue;
            }
            if (cp == '\n') {
                if (sawNonWs) {
                    lines.add(new KeptLine(lineNo, keep.toString()));
                }
                lineNo++;
                keep.setLength(0);
                sawNonWs = false;
                continue;
            }
            if (marks[i] == SourceScanner.Mark.KEEP) {
                keep.appendCodePoint(cp);
                if (!SourceScanner.isWhitespace(cp)) {
                    sawNonWs = true;
                }
            }
        }
        if (sawNonWs) {
            lines.add(new KeptLine(lineNo, keep.toString()));
        }
        return lines;
    }

    public static List<KeptLine> outlineLines(List<KeptLine> stripped, CompiledLanguage language) {
        if (stripped == null || stripped.isEmpty() || language == null) {
            return List.of();
        }
        List<KeptLine> kept = new ArrayList<>();
        for (KeptLine line : stripped) {
            if (language.matchesOutline(line.text())) {
                kept.add(line);
            }
        }
        return kept;
    }

    public static String formatNumbered(List<KeptLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        int width = 1;
        for (KeptLine line : lines) {
            width = Math.max(width, Integer.toString(line.originalNumber()).length());
        }
        StringBuilder out = new StringBuilder();
        for (KeptLine line : lines) {
            String number = Integer.toString(line.originalNumber());
            out.append(" ".repeat(width - number.length()));
            out.append(number);
            out.append("| ");
            out.append(line.text());
            out.append('\n');
        }
        return out.toString();
    }

    public static String joinPlain(List<KeptLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(lines.get(i).text());
        }
        return out.toString();
    }
}
