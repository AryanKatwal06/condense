package com.condense.corpus;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.CatalogBackedFilter;
import com.condense.filter.pipeline.config.BuiltinDefinition;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import com.condense.trust.Provenance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LeftoverBreadthFixtureTest {

    static Stream<String> leftoverNamesWithEmptyFixture() {
        List<String> names = new ArrayList<>();
        for (String name : BuiltinDefinitionCatalog.standalone().names()) {
            if (resourceExists("/fixtures/" + name + "/empty.txt")) {
                names.add(name);
            }
        }
        return names.stream();
    }

    @ParameterizedTest
    @MethodSource("leftoverNamesWithEmptyFixture")
    void emptyMalformedUnicodeOversizedAndOtherExitDoNotThrow(String name) throws Exception {
        BuiltinDefinition definition = BuiltinDefinitionCatalog.standalone().requiredDefinition(name);
        CatalogBackedFilter filter = new CatalogBackedFilter(name);
        String command = definition.commands().get(0);
        CondenseConfig config = CondenseConfig.defaults();

        String empty = load("/fixtures/" + name + "/empty.txt");
        FilterResult emptyResult = apply(filter, command, empty, 0, config);
        assertThat(body(emptyResult).trim())
            .as("%s empty must stay empty or the aggregate zero-issue header", name)
            .satisfiesAnyOf(
                text -> assertThat(text).isEmpty(),
                text -> assertThat(text).isEqualTo("0 issue(s) in 0 file(s)")
            );

        String malformed = load("/fixtures/" + name + "/malformed.txt");
        FilterResult[] malformedHolder = new FilterResult[1];
        assertThatCode(() -> malformedHolder[0] = apply(filter, command, malformed, 1, config))
            .as("%s malformed must not throw", name)
            .doesNotThrowAnyException();
        assertThat(malformedHolder[0]).isNotNull();
        if (!malformed.isBlank()) {
            assertThat(body(malformedHolder[0]).trim())
                .as("%s malformed must not become a clean zero-issue success", name)
                .isNotEqualTo("0 issue(s) in 0 file(s)");
        }

        String unicode = load("/fixtures/" + name + "/unicode.txt");
        FilterResult unicodeResult = apply(filter, command, unicode, 1, config);
        String unicodeBody = body(unicodeResult);
        String token = firstNonAsciiRun(unicode);
        if (token != null) {
            assertThat(unicodeBody)
                .as("%s unicode token %s must survive", name, token)
                .contains(token);
        }

        String oversized = load("/fixtures/" + name + "/oversized.txt");
        assertThatCode(() -> apply(filter, command, oversized, 0, config))
            .as("%s oversized must not hang or throw", name)
            .doesNotThrowAnyException();

        String other = firstExistingResource(
            "/fixtures/" + name + "/success.txt",
            "/fixtures/" + name + "/failure.txt"
        );
        if (other != null) {
            int exit = other.endsWith("failure.txt") ? 1 : 0;
            assertThatCode(() -> apply(filter, command, load(other), exit, config))
                .as("%s other-exit fixture must not throw", name)
                .doesNotThrowAnyException();
        }
    }

    private static FilterResult apply(
            CatalogBackedFilter filter,
            String command,
            String fixture,
            int exit,
            CondenseConfig config
    ) {
        ExecutionResult result = new ExecutionResult(exit, fixture, "", 10L);
        return filter.apply(command, result, config, 0, false);
    }

    private static String body(FilterResult result) {
        String output = result.output() == null ? "" : result.output();
        if (output.equals(Provenance.STAMP) || output.equals(Provenance.READ_STAMP)) {
            return "";
        }
        if (output.startsWith(Provenance.STAMP + "\n")) {
            return output.substring(Provenance.STAMP.length() + 1);
        }
        return output;
    }

    private static String firstNonAsciiRun(String text) {
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch > 127) {
                run.append(ch);
            } else if (run.length() > 0) {
                return run.toString();
            }
        }
        return run.length() > 0 ? run.toString() : null;
    }

    private static String load(String resource) throws Exception {
        try (var in = LeftoverBreadthFixtureTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean resourceExists(String resource) {
        return LeftoverBreadthFixtureTest.class.getResource(resource) != null;
    }

    private static String firstExistingResource(String... resources) {
        for (String resource : resources) {
            if (resourceExists(resource)) {
                return resource;
            }
        }
        return null;
    }
}
