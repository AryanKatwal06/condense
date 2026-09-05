package com.condense.read;

import com.condense.filter.strategy.BoundedRegex;
import com.condense.filter.strategy.RegexTimeoutException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Runtime view of a language definition with compiled outline patterns.
 */
public final class CompiledLanguage {

    public static final int MAX_OUTLINE_PATTERN_LENGTH = 500;

    private final LanguageDefinition definition;
    private final LanguageFamily family;
    private final RawStringStyle rawStyle;
    private final List<NamedPattern> outlines;

    CompiledLanguage(
            LanguageDefinition definition,
            LanguageFamily family,
            RawStringStyle rawStyle,
            List<NamedPattern> outlines
    ) {
        this.definition = definition;
        this.family = family;
        this.rawStyle = rawStyle;
        this.outlines = List.copyOf(outlines);
    }

    public LanguageDefinition definition() {
        return definition;
    }

    public LanguageFamily family() {
        return family;
    }

    public RawStringStyle rawStyle() {
        return rawStyle;
    }

    public String name() {
        return definition.name();
    }

    public boolean matchesOutline(String line) {
        if (family == LanguageFamily.MARKDOWN) {
            return isAtxHeading(line);
        }
        for (NamedPattern outline : outlines) {
            try {
                if (BoundedRegex.find(outline.pattern(), line == null ? "" : line)) {
                    return true;
                }
            } catch (RegexTimeoutException e) {
                return true;
            }
        }
        return false;
    }

    static boolean isAtxHeading(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.stripLeading();
        int hashes = 0;
        while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#' && hashes < 6) {
            hashes++;
        }
        return hashes > 0 && hashes < trimmed.length() && trimmed.charAt(hashes) == ' ';
    }

    record NamedPattern(String name, Pattern pattern) {}

    static List<String> compileOutlines(LanguageDefinition definition, LanguageFamily family, List<String> errors) {
        List<String> local = new ArrayList<>();
        if (family == LanguageFamily.DATA || family == LanguageFamily.MARKDOWN) {
            return local;
        }
        if (definition.outline().isEmpty()) {
            local.add(definition.name() + ": at least one [[outline]] pattern is required");
            return local;
        }
        for (int i = 0; i < definition.outline().size(); i++) {
            LanguageDefinition.OutlinePattern pattern = definition.outline().get(i);
            String path = definition.name() + ".outline[" + i + "]";
            if (pattern.regex() == null || pattern.regex().isBlank()) {
                local.add(path + ": regex is required");
                continue;
            }
            if (pattern.regex().length() > MAX_OUTLINE_PATTERN_LENGTH) {
                local.add(path + ": regex longer than " + MAX_OUTLINE_PATTERN_LENGTH);
                continue;
            }
            try {
                Pattern.compile(pattern.regex());
            } catch (Exception e) {
                local.add(path + ": invalid regex (" + e.getMessage() + ")");
            }
        }
        errors.addAll(local);
        return local;
    }

    static CompiledLanguage compile(LanguageDefinition definition) {
        LanguageFamily family = LanguageFamily.parse(definition.family());
        RawStringStyle raw = RawStringStyle.parse(definition.rawStrings());
        List<NamedPattern> outlines = new ArrayList<>();
        for (LanguageDefinition.OutlinePattern pattern : definition.outline()) {
            if (pattern.regex() == null || pattern.regex().isBlank()) {
                continue;
            }
            outlines.add(new NamedPattern(
                pattern.name() == null ? "outline" : pattern.name(),
                Pattern.compile(pattern.regex())
            ));
        }
        return new CompiledLanguage(definition, family, raw, outlines);
    }
}
