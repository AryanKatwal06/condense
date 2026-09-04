package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Porcelain XY status plus human-readable section parsing for {@code git status}.
 */
public final class GitStatusStage implements FilterStage {

    public static final GitStatusStage INSTANCE = new GitStatusStage();

    private static final Pattern CLEAN_PATTERN =
        Pattern.compile("nothing to commit", Pattern.CASE_INSENSITIVE);

    private GitStatusStage() {}

    @Override
    public StageResult process(String input, FilterContext context) {
        String raw = input != null ? input : "";
        if (isPorcelainFormat(raw)) {
            return StageResult.continueWith(parsePorcelain(raw, context.verbose(), context.ultraCompact()));
        }
        return StageResult.continueWith(parseHuman(raw, context.verbose(), context.ultraCompact()));
    }

    static boolean isPorcelainFormat(String raw) {
        return raw.lines().limit(5).allMatch(line ->
            line.length() >= 3
                && line.charAt(2) == ' '
                && "MADRCU?! ".indexOf(line.charAt(0)) >= 0
                && "MADRCU?! ".indexOf(line.charAt(1)) >= 0
        );
    }

    private static String parsePorcelain(String raw, int verbose, boolean ultraCompact) {
        int staged = 0;
        int modified = 0;
        int untracked = 0;
        List<String> changedFiles = new ArrayList<>();

        for (String line : raw.lines().toList()) {
            if (line.length() < 3) {
                continue;
            }
            char index = line.charAt(0);
            char work = line.charAt(1);
            String path = line.substring(3);

            if (index == '?' && work == '?') {
                untracked++;
                changedFiles.add("? " + path);
            } else {
                if (index != ' ' && index != '?') {
                    staged++;
                    changedFiles.add("S " + path);
                }
                if (work != ' ' && work != '?') {
                    modified++;
                    changedFiles.add("M " + path);
                }
            }
        }

        if (staged == 0 && modified == 0 && untracked == 0) {
            return "✓ clean";
        }
        String summary = buildSummary("", staged, modified, untracked, ultraCompact);
        if (verbose >= 2 && !changedFiles.isEmpty()) {
            StringBuilder sb = new StringBuilder(summary).append('\n');
            changedFiles.forEach(f -> sb.append("  ").append(f).append('\n'));
            return sb.toString().stripTrailing();
        }
        return summary;
    }

    private static String parseHuman(String raw, int verbose, boolean ultraCompact) {
        String branch = null;
        boolean isClean = false;
        int staged = 0;
        int modified = 0;
        int untracked = 0;
        List<String> changedFiles = new ArrayList<>();

        enum Section { NONE, STAGED, UNSTAGED, UNTRACKED }
        Section currentSection = Section.NONE;

        for (String line : raw.lines().toList()) {
            if (branch == null) {
                if (line.startsWith("On branch ")) {
                    branch = line.substring(10).trim();
                } else if (line.startsWith("HEAD detached at ")) {
                    branch = "detached@" + line.substring(17).trim();
                }
            }
            if (BoundedRegex.find(CLEAN_PATTERN, line)) {
                isClean = true;
            }

            if (line.startsWith("Changes to be committed:")) {
                currentSection = Section.STAGED;
                continue;
            } else if (line.startsWith("Changes not staged for commit:")) {
                currentSection = Section.UNSTAGED;
                continue;
            } else if (line.startsWith("Untracked files:")) {
                currentSection = Section.UNTRACKED;
                continue;
            } else if (line.isEmpty()) {
                currentSection = Section.NONE;
                continue;
            }

            if (line.startsWith("\t")) {
                String fileLine = line.substring(1).trim();
                if (currentSection == Section.STAGED) {
                    staged++;
                    changedFiles.add("S " + fileLine);
                } else if (currentSection == Section.UNSTAGED) {
                    modified++;
                    changedFiles.add("M " + fileLine);
                } else if (currentSection == Section.UNTRACKED) {
                    untracked++;
                    changedFiles.add("? " + fileLine);
                }
            }
        }

        String prefix = branch != null ? "[" + branch + "] " : "";
        if (isClean) {
            return prefix + "✓ clean";
        }
        String summary = buildSummary(prefix, staged, modified, untracked, ultraCompact);
        if (verbose >= 2 && !changedFiles.isEmpty()) {
            StringBuilder sb = new StringBuilder(summary).append('\n');
            for (String file : changedFiles) {
                sb.append("  ").append(file).append('\n');
            }
            return sb.toString().stripTrailing();
        }
        return summary;
    }

    private static String buildSummary(String prefix, int staged, int modified,
                                       int untracked, boolean ultraCompact) {
        if (ultraCompact) {
            StringBuilder sb = new StringBuilder(prefix);
            if (staged > 0) {
                sb.append("↑S:").append(staged).append(' ');
            }
            if (modified > 0) {
                sb.append("M:").append(modified).append(' ');
            }
            if (untracked > 0) {
                sb.append("?:").append(untracked).append(' ');
            }
            String result = sb.toString().stripTrailing();
            return result.equals(prefix.stripTrailing()) ? prefix + "✓ clean" : result;
        }

        List<String> parts = new ArrayList<>(3);
        if (staged > 0) {
            parts.add("staged: " + staged);
        }
        if (modified > 0) {
            parts.add("modified: " + modified);
        }
        if (untracked > 0) {
            parts.add("untracked: " + untracked);
        }
        return parts.isEmpty() ? prefix + "✓ clean" : prefix + String.join(" | ", parts);
    }
}
