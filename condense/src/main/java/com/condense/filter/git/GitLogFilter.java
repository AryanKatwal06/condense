package com.condense.filter.git;

import com.condense.annotation.CommandFilter;
import com.condense.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("git log")
@ApplicationScoped
public class GitLogFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(GitLogFilter.class);

    // "commit abc1234def456..." — extract first 8 chars
    private static final Pattern COMMIT_PATTERN =
        Pattern.compile("^commit ([0-9a-f]{8})[0-9a-f]*");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result);

        try {
            String stdout = result.readStdout();
            List<String> lines = stdout.lines().toList();
            List<String> commits = new ArrayList<>();

            String currentHash = null;
            for (String line : lines) {
                var m = COMMIT_PATTERN.matcher(line);
                if (m.find()) {
                    currentHash = m.group(1);
                } else if (currentHash != null && !line.isBlank()
                        && !line.startsWith("Author:")
                        && !line.startsWith("Date:")
                        && !line.startsWith("Merge:")) {
                    // First non-blank, non-header line after commit = message
                    commits.add(currentHash + " " + line.trim());
                    currentHash = null; // Reset until next commit line
                }
            }

            if (commits.isEmpty()) return FilterResult.of(result, "(no commits)");

            int limit = verbose >= 2 ? commits.size() : Math.min(10, commits.size());
            String out = String.join("\n", commits.subList(0, limit));
            if (!ultraCompact && commits.size() > limit) {
                out += "\n(+" + (commits.size() - limit) + " more)";
            }
            return FilterResult.of(result, out);

        } catch (Exception e) {
            log.warnf("GitLogFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result);
        }
    }
}