package com.condense.discover;

import com.condense.filter.pipeline.config.DefinitionMappers;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoverCatalogLoadTest {

    @Test
    void catalogLoadsIndexedRulesAndOrdersJsInstallByPriority() {
        DiscoverRuleCatalog catalog = DiscoverRuleCatalog.standalone();
        assertThat(catalog.names()).contains("js-pnpm", "js-npm-lock", "js-npm", "git", "extra-prisma");
        List<String> js = catalog.rules().stream()
            .filter(rule -> "js-install".equals(rule.family()))
            .map(DiscoverDefinition::name)
            .toList();
        assertThat(js).containsExactly("js-pnpm", "js-npm-lock", "js-npm");
        assertThat(catalog.required("js-pnpm").recommend()).containsExactly("pnpm-install");
    }

    @Test
    void unknownRuleNameFails() {
        assertThatThrownBy(() -> DiscoverRuleCatalog.standalone().required("not-a-rule"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown discover rule");
    }

    @Test
    void validatorPassesShippedRules() {
        assertThat(DiscoverDefinitionValidator.validate()).isEmpty();
    }

    @Test
    void unknownKeyIsRejected() throws Exception {
        byte[] bytes = """
            schema_version = 1
            name = "x"
            family = "x"
            priority = 10
            mystery = true
            signals = ["package.json"]
            recommend = ["npm-install"]
            """.getBytes();
        assertThatThrownBy(() -> DefinitionMappers.STRICT_TOML.readValue(bytes, DiscoverDefinition.class))
            .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    void globPathIsRejected() {
        List<String> errors = new ArrayList<>();
        DiscoverRuleCatalog.validateRelativePath("signals[0]", "src/**/package.json", errors);
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0)).contains("exact relative");
    }
}
