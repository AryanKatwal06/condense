package com.condense.filter.pipeline.config;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes {@code [[tests]]} blocks from a builtin definition through {@link StageFactory}.
 */
public final class InlineDefinitionTestRunner {

    private InlineDefinitionTestRunner() {}

    public static List<String> run(BuiltinDefinition definition) {
        List<String> failures = new ArrayList<>();
        if (definition == null) {
            failures.add("definition is null");
            return failures;
        }
        FilterPipeline pipeline = StageFactory.buildPipeline(definition.stages());
        for (BuiltinDefinition.InlineTest test : definition.tests()) {
            String id = test.id() != null ? test.id() : "(unnamed)";
            String input = test.input() != null ? test.input() : "";
            String expected = test.expected() != null ? test.expected() : "";
            String actual = pipeline.execute(input, FilterContext.empty());
            if (!normalizeNewlines(expected).equals(normalizeNewlines(actual))) {
                failures.add(definition.name() + "/" + id
                    + " expected <" + expected + "> but was <" + actual + ">");
            }
        }
        return failures;
    }

    public static List<String> runAll(Iterable<BuiltinDefinition> definitions) {
        List<String> failures = new ArrayList<>();
        for (BuiltinDefinition definition : definitions) {
            failures.addAll(run(definition));
        }
        return failures;
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
