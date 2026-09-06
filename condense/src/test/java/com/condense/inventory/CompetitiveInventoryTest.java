package com.condense.inventory;

import com.condense.filter.pipeline.config.BuiltinDefinition;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompetitiveInventoryTest {

    static final String PIN_COMMIT = "d9498bb";

    @Test
    void pinnedZapFamiliesHaveRegisteredCondensePrefixes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try (var in = CompetitiveInventoryTest.class.getResourceAsStream("/inventory/zap-families.json")) {
            assertThat(in).as("zap-families.json must be on the test classpath").isNotNull();
            root = mapper.readTree(in);
        }

        assertThat(root.path("zap_commit").asText()).isEqualTo(PIN_COMMIT);

        BuiltinDefinitionCatalog catalog = BuiltinDefinitionCatalog.standalone();
        Set<String> registered = new LinkedHashSet<>();
        for (BuiltinDefinition definition : catalog.all()) {
            registered.addAll(definition.commands());
        }

        Set<String> ids = new LinkedHashSet<>();
        JsonNode families = root.path("families");
        assertThat(families.isArray()).isTrue();
        assertThat(families).isNotEmpty();

        for (JsonNode family : families) {
            String id = family.path("id").asText();
            assertThat(id).as("family id must not be blank").isNotBlank();
            assertThat(ids.add(id)).as("duplicate family id %s", id).isTrue();

            if (family.hasNonNull("exclusion")) {
                assertThat(family.path("exclusion").path("kind").asText())
                    .as("%s exclusion kind", id)
                    .isNotBlank();
                assertThat(family.path("exclusion").path("reason").asText())
                    .as("%s exclusion reason", id)
                    .isNotBlank();
                continue;
            }

            String definitionName = family.path("condense_definition").asText();
            assertThat(definitionName)
                .as("%s must name a Condense definition (aliases alone do not count)", id)
                .isNotBlank();
            BuiltinDefinition definition = catalog.requiredDefinition(definitionName);

            Set<String> claimed = new LinkedHashSet<>(definition.commands());
            boolean ownsAZapCommand = false;
            for (JsonNode commandNode : family.path("zap_commands")) {
                String command = commandNode.asText();
                assertThat(registered)
                    .as("zap command %s on family %s must be a registered Condense prefix", command, id)
                    .contains(command);
                if (claimed.contains(command)) {
                    ownsAZapCommand = true;
                }
            }
            assertThat(ownsAZapCommand)
                .as("%s points at %s but that definition claims none of the zap commands", id, definitionName)
                .isTrue();
        }
    }
}
