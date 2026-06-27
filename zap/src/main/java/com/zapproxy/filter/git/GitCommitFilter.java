package com.zapproxy.filter.git;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("git commit")
@ApplicationScoped
public class GitCommitFilter implements FilterStrategy {

    // "[main abc1234] commit message"
    private static final Pattern COMMIT_LINE =
        Pattern.compile("^\\[([^\\]]+)\\s+([0-9a-f]+)\\]\\s+(.+)$", Pattern.MULTILINE);

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        Matcher m = COMMIT_LINE.matcher(raw);
        if (m.find()) {
            String branch  = m.group(1).trim();
            String hash    = m.group(2).substring(0, Math.min(8, m.group(2).length()));
            String message = m.group(3).trim();
            String out = ultraCompact
                ? "[" + branch + "] " + hash + " " + message
                : "✓ committed [" + branch + "] " + hash + " — " + message;
            return FilterResult.of(raw, out);
        }

        return FilterResult.of(raw, "✓ committed");
    }
}