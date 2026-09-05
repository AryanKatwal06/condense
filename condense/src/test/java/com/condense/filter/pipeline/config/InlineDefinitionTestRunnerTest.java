package com.condense.filter.pipeline.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InlineDefinitionTestRunnerTest {

    @Test
    void everyShippedDefinitionHasAtLeastOnePassingInlineTest() {
        BuiltinDefinitionCatalog catalog = BuiltinDefinitionCatalog.standalone();
        assertThat(catalog.all()).hasSizeGreaterThanOrEqualTo(31);
        for (BuiltinDefinition definition : catalog.all()) {
            assertThat(definition.tests())
                .as(definition.name() + " must have inline tests")
                .isNotEmpty();
        }
        List<String> failures = InlineDefinitionTestRunner.runAll(catalog.all());
        assertThat(failures).isEmpty();
    }

    @Test
    void wrongExpectedFailsTheRunner() {
        BuiltinDefinition definition = BuiltinDefinitionCatalog.standalone().requiredDefinition("git-add");
        BuiltinDefinition broken = new BuiltinDefinition(
            definition.schemaVersion(),
            definition.name(),
            definition.commands(),
            definition.stages(),
            List.of(new BuiltinDefinition.InlineTest("broken", "", "not-this")),
            definition.selectInput(),
            definition.gate()
        );
        assertThat(InlineDefinitionTestRunner.run(broken)).isNotEmpty();
    }
}
