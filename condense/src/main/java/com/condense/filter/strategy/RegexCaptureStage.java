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

    @Override
    public StageResult process(String input, FilterContext context) {
        Matcher matcher = BoundedRegex.matcher(pattern, input != null ? input : "");
        if (matcher.find()) {
            return StageResult.continueWith(formatter.apply(matcher, context));
        }
        return StageResult.continueWith(fallback);
    }
}
