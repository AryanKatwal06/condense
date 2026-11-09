package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps useful grep matches while bounding unusually large search results. */
@DeclarativeStage(aliases = {"grep_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class GrepSummaryStage implements FilterStage {

    public static final GrepSummaryStage INSTANCE = new GrepSummaryStage();
    static final int SMALL_RESULT_LINES = 40;
    static final int MAX_MATCHES_PER_FILE = 12;
    static final int MAX_MATCH_LINES = 80;

    private GrepSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        String text = raw != null ? raw : "";
        Map<String, List<String>> byFile = new LinkedHashMap<>();
        int total = 0;
        for (String line : text.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            total++;
            String file = fileKey(line);
            byFile.computeIfAbsent(file, ignored -> new ArrayList<>()).add(line.strip());
        }
        if (total == 0 || total <= SMALL_RESULT_LINES) {
            return StageResult.continueWith(text);
        }

        List<String> visible = new ArrayList<>();
        int omitted = 0;
        for (List<String> fileLines : byFile.values()) {
            int kept = 0;
            for (String line : fileLines) {
                if (kept >= MAX_MATCHES_PER_FILE || visible.size() >= MAX_MATCH_LINES) {
                    omitted++;
                    continue;
                }
                visible.add(line);
                kept++;
            }
        }
        int files = byFile.size();
        StringBuilder output = new StringBuilder().append(total).append(" match(es) in ")
            .append(files).append(" file(s)\n");
        for (String line : visible) {
            output.append(line).append('\n');
        }
        if (omitted > 0) {
            output.append("... (").append(omitted).append(" matches omitted) ...\n");
        }
        return StageResult.continueWith(output.toString().stripTrailing());
    }

    /** Extracts the path before line/column delimiters without splitting C:\\ paths at drive colon. */
    static String fileKey(String line) {
        int from = isWindowsDrivePath(line) ? 2 : 0;
        int colon = line.indexOf(':', from);
        if (colon < 0) {
            return line;
        }
        int second = line.indexOf(':', colon + 1);
        if (second < 0) {
            return line.substring(0, colon);
        }
        String lineNumber = line.substring(colon + 1, second);
        if (isDigits(lineNumber)) {
            return line.substring(0, colon);
        }
        return line.substring(0, colon);
    }

    private static boolean isWindowsDrivePath(String line) {
        return line.length() >= 3 && Character.isLetter(line.charAt(0)) && line.charAt(1) == ':'
            && (line.charAt(2) == '\\' || line.charAt(2) == '/');
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
