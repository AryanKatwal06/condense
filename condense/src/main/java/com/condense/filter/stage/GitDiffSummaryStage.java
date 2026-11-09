package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.ir.Document;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Condenses {@code git diff} output without destroying the actual change.
 *
 * <p>Unified patches keep their file headers, hunk headers, and a bounded
 * window of added/removed/context lines. Only explicit {@code --stat},
 * {@code --shortstat}, {@code --numstat}, or stat-only output is reduced to
 * the short summary form.
 */
@DeclarativeStage(aliases = {"git_diff_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class GitDiffSummaryStage implements FilterStage {

    public static final GitDiffSummaryStage INSTANCE = new GitDiffSummaryStage();

    private static final Pattern STAT_SUMMARY = Pattern.compile("(\\d+) files? changed.*");
    private static final Pattern STAT_FILE_LINE = Pattern.compile("^\\s+\\S.*\\|\\s*\\d+");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ .*@@.*$");

    /** Lines kept from the start of an oversized hunk. */
    static final int HUNK_HEAD_LINES = 40;
    /** Lines kept from the end of an oversized hunk. */
    static final int HUNK_TAIL_LINES = 20;
    /** Total rendered diff line budget (excluding the final omission marker). */
    static final int MAX_OUTPUT_LINES = 360;
    /** Total rendered diff byte budget (UTF-8). */
    static final int MAX_OUTPUT_BYTES = 6 * 1024;

    private GitDiffSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        String text = raw != null ? raw : "";
        String command = context != null ? context.command() : "";
        List<String> argv = context != null ? context.argv() : List.of();
        boolean statRequested = requestsStat(command, argv);
        // An explicit stat flag wins only when there is no real patch header.
        // git diff --stat -p still contains a patch and must never lose it.
        boolean unified = hasUnifiedDiffHeaders(text)
            && !(statRequested && !text.contains("diff --git "));
        String output = unified
            ? (context != null && context.ultraCompact() ? compactDiffSummary(text) : unifiedOutput(text))
            : statOutput(text, context);
        if (output.isBlank()) {
            output = "no changes";
        }

        if (context != null && context.documentBuilder() != null) {
            // The summary is the rendered text, exactly as the status and add
            // stages do it. A count-only summary here would make TextRenderer
            // disagree with the preserved patch that apply() returns.
            context.documentBuilder().git(new Document.GitDocument(
                "",
                "no changes".equals(output),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                output,
                "diff",
                output.lines().toList()
            ));
        }

        return StageResult.continueWith(output);
    }

    /**
     * True when the raw text is a unified patch rather than a stat table.
     */
    public static boolean hasUnifiedDiffHeaders(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean hunk = false;
        boolean oldFile = false;
        boolean newFile = false;
        for (String line : text.lines().toList()) {
            hunk |= line.startsWith("diff --git ") || line.startsWith("@@ ")
                || line.startsWith("Binary files ");
            oldFile |= line.startsWith("--- ");
            newFile |= line.startsWith("+++ ");
        }
        return hunk || (oldFile && newFile);
    }

    public static String compactDiffSummary(String text) {
        int additions = 0;
        int deletions = 0;
        for (String line : (text != null ? text : "").lines().toList()) {
            if (line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }
            if (line.startsWith("+")) {
                additions++;
            } else if (line.startsWith("-")) {
                deletions++;
            }
        }
        return "+" + additions + " / -" + deletions + " lines";
    }

    private String statOutput(String text, FilterContext context) {
        Matcher m = BoundedRegex.matcher(STAT_SUMMARY, text);
        if (m.find()) {
            String summary = m.group(0).trim();
            if (context != null && context.verbose() >= 2) {
                StringBuilder sb = new StringBuilder(summary).append('\n');
                text.lines()
                    .filter(l -> BoundedRegex.find(STAT_FILE_LINE, l))
                    .forEach(l -> sb.append("  ").append(l.trim()).append('\n'));
                return sb.toString().stripTrailing();
            }
            return summary;
        }
        // --numstat / --shortstat and empty diffs must not lose their rows.
        String trimmed = text.stripTrailing();
        return trimmed.isBlank() ? "no changes" : trimmed;
    }

    /**
     * State-machine walk over a unified patch. Plumbing is removed, structure is
     * retained, and both per-hunk and total budgets emit explicit omission markers.
     */
    static String unifiedOutput(String text) {
        List<String> lines = text.lines().toList();
        int totalLines = lines.size();
        List<Candidate> candidates = new ArrayList<>();

        int i = 0;
        while (i < totalLines) {
            String line = lines.get(i);

            if (isPlumbing(line)) {
                i++;
                continue;
            }

            if (line.startsWith("@@ ")) {
                int end = i + 1;
                while (end < totalLines) {
                    String next = lines.get(end);
                    if (next.startsWith("@@ ") || next.startsWith("diff --git ")) {
                        break;
                    }
                    end++;
                }
                List<String> body = lines.subList(i + 1, end);
                candidates.add(new Candidate(line, i + 1));
                boolean[] keep = selectHunkLines(body);
                int b = 0;
                while (b < body.size()) {
                    if (keep[b]) {
                        candidates.add(new Candidate(body.get(b), i + 2 + b));
                        b++;
                        continue;
                    }
                    int omittedStart = b;
                    while (b < body.size() && !keep[b]) {
                        b++;
                    }
                    candidates.add(new Candidate(
                        "... (" + (b - omittedStart) + " hunk lines omitted) ...", i + 1 + b));
                }
                i = end;
                continue;
            }

            candidates.add(new Candidate(line, i + 1));
            i++;
        }

        List<String> out = new ArrayList<>();
        int bytes = 0;
        int rawConsumed = 0;
        boolean truncated = false;
        for (Candidate candidate : candidates) {
            int add = candidate.text().getBytes(StandardCharsets.UTF_8).length + 1;
            if (!out.isEmpty()
                && (out.size() >= MAX_OUTPUT_LINES || bytes + add > MAX_OUTPUT_BYTES)) {
                truncated = true;
                break;
            }
            out.add(candidate.text());
            bytes += add;
            rawConsumed = candidate.rawEnd();
        }

        if (truncated) {
            int omitted = Math.max(1, totalLines - rawConsumed);
            out.add("... (" + omitted + " diff lines omitted) ...");
        }
        return String.join("\n", out);
    }

    private static boolean[] selectHunkLines(List<String> body) {
        int budget = HUNK_HEAD_LINES + HUNK_TAIL_LINES;
        boolean[] keep = new boolean[body.size()];
        if (body.size() <= budget) {
            java.util.Arrays.fill(keep, true);
            return keep;
        }

        List<Integer> changes = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            if (isChangeLine(body.get(i))) {
                changes.add(i);
            }
        }
        int selected = 0;
        if (changes.size() <= budget) {
            for (int index : changes) {
                keep[index] = true;
                selected++;
            }
        } else {
            int headChanges = HUNK_HEAD_LINES;
            int tailChanges = HUNK_TAIL_LINES;
            for (int i = 0; i < headChanges; i++) {
                keep[changes.get(i)] = true;
                selected++;
            }
            for (int i = changes.size() - tailChanges; i < changes.size(); i++) {
                keep[changes.get(i)] = true;
                selected++;
            }
        }
        // Favor the immediate context around changes before filling the remaining
        // window with the conventional head/tail sample.
        for (int distance = 1; distance <= 2 && selected < budget; distance++) {
            for (int i = 0; i < body.size() && selected < budget; i++) {
                if (keep[i]) {
                    for (int neighbor : new int[] {i - distance, i + distance}) {
                        if (selected < budget && neighbor >= 0 && neighbor < body.size()
                            && !keep[neighbor] && !isChangeLine(body.get(neighbor))) {
                            keep[neighbor] = true;
                            selected++;
                        }
                    }
                }
            }
        }
        int headKept = 0;
        for (int i = 0; i < body.size() && selected < budget
            && headKept < HUNK_HEAD_LINES; i++) {
            if (!keep[i]) {
                keep[i] = true;
                selected++;
                headKept++;
            }
        }
        for (int i = body.size() - 1; i >= 0 && selected < budget; i--) {
            if (!keep[i]) {
                keep[i] = true;
                selected++;
            }
        }
        return keep;
    }

    private static boolean isChangeLine(String line) {
        return (line.startsWith("+") && !line.startsWith("+++"))
            || (line.startsWith("-") && !line.startsWith("---"));
    }

    private static boolean isPlumbing(String line) {
        return line.startsWith("index ")
            || line.startsWith("similarity index ")
            || line.startsWith("dissimilarity index ");
    }

    /** True when the command explicitly asked for a stat table instead of a patch. */
    static boolean requestsStat(String command, List<String> argv) {
        List<String> tokens = argv != null && !argv.isEmpty()
            ? argv
            : (command == null || command.isBlank()
                ? List.of()
                : List.of(command.trim().split("\\s+")));
        for (String token : tokens) {
            String value = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
            if (value.equals("--stat") || value.equals("--shortstat") || value.equals("--numstat")
                || value.startsWith("--stat=")
                || value.startsWith("--shortstat=")
                || value.startsWith("--numstat=")) {
                return true;
            }
        }
        return false;
    }

    private record Candidate(String text, int rawEnd) {}
}
