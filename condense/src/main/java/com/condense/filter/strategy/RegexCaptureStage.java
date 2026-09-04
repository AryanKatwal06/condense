package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a bounded regex against the whole input and formats the first match.
 */
public final class RegexCaptureStage implements FilterStage {

    private final Pattern pattern;
    private final BiFunction<Matcher, FilterContext, String> formatter;
    private final String fallback;

    public RegexCaptureStage(Pattern pattern,
                             BiFunction<Matcher, FilterContext, String> formatter,
                             String fallback) {
        this.pattern = Objects.requireNonNull(pattern, "pattern");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.fallback = fallback != null ? fallback : "";
    }

    /**
     * Declarative constructor. {@code format} may contain {@code $1}, {@code $2}, {@code $0}.
     */
    public RegexCaptureStage(Pattern pattern, String format, String fallback) {
        this(pattern, (matcher, ignored) -> expandTemplate(format != null ? format : "$0", matcher), fallback);
    }

    static String expandTemplate(String format, Matcher matcher) {
        String out = format;
        for (int i = matcher.groupCount(); i >= 1; i--) {
            String group = matcher.group(i);
            out = out.replace("$" + i, group != null ? group : "");
        }
        String whole = matcher.group(0);
        return out.replace("$0", whole != null ? whole : "");
    }

    @Override
    public StageResult process(String input, FilterContext context) {
        Matcher matcher = BoundedRegex.matcher(pattern, input != null ? input : "");
        if (matcher.find()) {
            return StageResult.continueWith(formatter.apply(matcher, context));
        }
        return StageResult.continueWith(fallback);
    }
}
