package com.zapproxy.filter.git;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("git push")
@ApplicationScoped
public class GitPushFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(GitPushFilter.class);

    private static final Pattern BRANCH_PATTERN =
        Pattern.compile("\\s+(\\S+)\\s+->\\s+(\\S+)");
    private static final Pattern UP_TO_DATE =
        Pattern.compile("Everything up-to-date", Pattern.CASE_INSENSITIVE);
    private static final Pattern REJECTED =
        Pattern.compile("\\[rejected\\]|error:|failed to push");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        String raw = result.combined();

        if (REJECTED.matcher(raw).find()) {
            // Push failed — return stderr so AI sees the rejection reason
            return FilterResult.passthrough(result.stderr().isBlank() ? raw : result.stderr());
        }

        if (UP_TO_DATE.matcher(raw).find()) {
            return FilterResult.of(raw, "✓ up-to-date (nothing pushed)");
        }

        Matcher m = BRANCH_PATTERN.matcher(raw);
        if (m.find()) {
            String dest = m.group(2).trim();
            return FilterResult.of(raw, "✓ pushed → " + dest);
        }

        // Fallback: just confirm success
        return result.succeeded()
            ? FilterResult.of(raw, "✓ pushed")
            : FilterResult.passthrough(raw);
    }
}