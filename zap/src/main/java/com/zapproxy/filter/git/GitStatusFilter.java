package com.zapproxy.filter.git;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.ExecutionResult;
import com.zapproxy.core.FilterResult;
import com.zapproxy.core.FilterStrategy;
import com.zapproxy.core.ZapConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filters {@code git status} output into a compact single-line summary.
 *
 * <h2>Output examples</h2>
 * <pre>
 * Normal:       [main] staged: 2 | modified: 1 | untracked: 3
 * Clean:        [main] ✓ clean
 * Ultra-compact: [main] ↑S:2 M:1 ?:3
 * Verbose:      [main] staged: 2 | modified: 1 | untracked: 3
 *                 A src/main/java/com/zapproxy/NewFile.java
 *                 m pom.xml
 *                 ? notes.txt
 * </pre>
 *
 * <h2>Failure passthrough</h2>
 * Non-zero exit (e.g. exit 128: "fatal: not a git repository") is passed
 * through unchanged so the AI sees the actual git error.
 */
@CommandFilter("git status")
@ApplicationScoped
public class GitStatusFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(GitStatusFilter.class);

    private static final Pattern BRANCH_PATTERN =
        Pattern.compile("^On branch (.+)$", Pattern.MULTILINE);

    private static final Pattern DETACHED_PATTERN =
        Pattern.compile("^HEAD detached at (.+)$", Pattern.MULTILINE);

    private static final Pattern CLEAN_PATTERN =
        Pattern.compile("nothing to commit", Pattern.CASE_INSENSITIVE);

    @Override
    public FilterResult apply(
            String command,
            ExecutionResult result,
            ZapConfig config,
            int verbose,
            boolean ultraCompact) {

        // Non-zero exit: git error (not-a-repo, permission denied, etc.)
        if (!result.succeeded()) {
            log.debugf("git status exited %d — passing through", result.exitCode());
            return FilterResult.passthrough(result);
        }

        try {
            return filter(result, verbose, ultraCompact);
        } catch (Exception e) {
            log.warnf("GitStatusFilter parse error: %s — falling back to passthrough",
                e.getMessage());
            return FilterResult.passthrough(result);
        }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private FilterResult filter(ExecutionResult result, int verbose, boolean ultraCompact) {
        String branch = null;
        boolean isClean = false;

        int staged    = 0;
        int modified  = 0;
        int untracked = 0;
        List<String> changedFiles = new ArrayList<>();

        enum Section { NONE, STAGED, UNSTAGED, UNTRACKED }
        Section currentSection = Section.NONE;

        try (java.util.stream.Stream<String> stream = result.stdoutLines()) {
            for (String line : (Iterable<String>) stream::iterator) {
                if (branch == null) {
                    if (line.startsWith("On branch ")) branch = line.substring(10).trim();
                    else if (line.startsWith("HEAD detached at ")) branch = "detached@" + line.substring(17).trim();
                }
                if (CLEAN_PATTERN.matcher(line).find()) isClean = true;

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
        } catch (java.io.IOException e) {
            return FilterResult.passthrough(result);
        }

        String prefix = branch != null ? "[" + branch + "] " : "";

        // Clean working tree
        if (isClean) {
            return FilterResult.of(result, prefix + "✓ clean");
        }

        String summary = buildSummary(prefix, staged, modified, untracked, ultraCompact);

        if (verbose >= 2 && !changedFiles.isEmpty()) {
            StringBuilder sb = new StringBuilder(summary).append('\n');
            for (String file : changedFiles) {
                sb.append("  ").append(file).append('\n');
            }
            return FilterResult.of(result, sb.toString().stripTrailing());
        }

        return FilterResult.of(result, summary);
    }

    private String buildSummary(String prefix, int staged, int modified,
                                int untracked, boolean ultraCompact) {
        if (ultraCompact) {
            StringBuilder sb = new StringBuilder(prefix);
            if (staged    > 0) sb.append("↑S:").append(staged).append(' ');
            if (modified  > 0) sb.append("M:").append(modified).append(' ');
            if (untracked > 0) sb.append("?:").append(untracked).append(' ');
            String result = sb.toString().stripTrailing();
            return result.equals(prefix.stripTrailing()) ? prefix + "✓ clean" : result;
        }

        List<String> parts = new ArrayList<>(3);
        if (staged    > 0) parts.add("staged: "    + staged);
        if (modified  > 0) parts.add("modified: "  + modified);
        if (untracked > 0) parts.add("untracked: " + untracked);

        return parts.isEmpty()
            ? prefix + "✓ clean"
            : prefix + String.join(" | ", parts);
    }
}
