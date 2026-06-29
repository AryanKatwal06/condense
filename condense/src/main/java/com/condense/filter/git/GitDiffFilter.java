package com.condense.filter.git;

import com.condense.annotation.CommandFilter;
import com.condense.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("git diff")
@ApplicationScoped
public class GitDiffFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(GitDiffFilter.class);

    // Matches "3 files changed, 45 insertions(+), 12 deletions(-)"
    private static final Pattern STAT_SUMMARY =
        Pattern.compile("(\\d+) files? changed.*");

    // Matches diff stat file lines: " src/Foo.java | 12 ++"
    private static final Pattern STAT_FILE_LINE =
        Pattern.compile("^\\s+\\S.*\\|\\s*\\d+");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded() && result.readStdout().isBlank()) {
            return FilterResult.passthrough(result);
        }

        try {
            String stdout = result.readStdout();
            String raw = stdout.isBlank() ? result.readStderr() : stdout;

            // Look for --stat summary line
            Matcher m = STAT_SUMMARY.matcher(raw);
            if (m.find()) {
                String summary = m.group(0).trim();
                if (verbose >= 2) {
                    // Include per-file stats
                    StringBuilder sb = new StringBuilder(summary).append('\n');
                    raw.lines()
                        .filter(l -> STAT_FILE_LINE.matcher(l).find())
                        .forEach(l -> sb.append("  ").append(l.trim()).append('\n'));
                    return FilterResult.of(result, sb.toString().stripTrailing());
                }
                return FilterResult.of(result, summary);
            }

            // Plain diff — count patch lines
            long added   = raw.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).count();
            long removed = raw.lines().filter(l -> l.startsWith("-") && !l.startsWith("---")).count();

            if (added == 0 && removed == 0) {
                return FilterResult.of(result, "no changes");
            }

            String summary = "+" + added + " / -" + removed + " lines";
            return FilterResult.of(result, summary);

        } catch (Exception e) {
            log.warnf("GitDiffFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result);
        }
    }
}