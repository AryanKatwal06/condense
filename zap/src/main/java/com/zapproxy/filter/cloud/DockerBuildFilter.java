package com.zapproxy.filter.cloud;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.AnsiStripStrategy;
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
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        String clean = AnsiStripStrategy.strip(raw);

        StringBuilder sb = new StringBuilder("✓ docker build");
        Matcher id = IMAGE_ID.matcher(clean);
        if (id.find()) sb.append(": ").append(id.group(1));
        Matcher tag = TAGGED.matcher(clean);
        if (tag.find()) sb.append(" → ").append(tag.group(1).trim());

        return FilterResult.of(raw, sb.toString());
    }
}