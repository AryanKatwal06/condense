package com.condense.filter.cloud;

import com.condense.annotation.CommandFilter;
import com.condense.core.*;
import com.condense.filter.strategy.AnsiStripStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("docker build")
@ApplicationScoped
public class DockerBuildFilter implements FilterStrategy {

    private static final Pattern IMAGE_ID =
        Pattern.compile("(?:Successfully built|writing image sha256:)\\s*([0-9a-f]{8,12})");
    private static final Pattern TAGGED =
        Pattern.compile("Successfully tagged (.+)");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result);

        String raw = result.readStdout().isBlank() ? result.readStderr() : result.readStdout();
        String clean = AnsiStripStrategy.strip(raw);

        StringBuilder sb = new StringBuilder("✓ docker build");
        Matcher id = IMAGE_ID.matcher(clean);
        if (id.find()) sb.append(": ").append(id.group(1));
        Matcher tag = TAGGED.matcher(clean);
        if (tag.find()) sb.append(" → ").append(tag.group(1).trim());

        return FilterResult.of(result, sb.toString());
    }
}