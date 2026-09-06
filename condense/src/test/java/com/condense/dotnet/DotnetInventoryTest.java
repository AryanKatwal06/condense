package com.condense.dotnet;

import com.condense.filter.pipeline.config.BuiltinDefinition;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DotnetInventoryTest {

    @Test
    void leftoverCatalogMeetsAndExceedsZapDotnetCommands() throws Exception {
        Set<String> registered = new LinkedHashSet<>();
        for (BuiltinDefinition definition : BuiltinDefinitionCatalog.standalone().all()) {
            registered.addAll(definition.commands());
        }

        ObjectMapper mapper = new ObjectMapper();
        try (var in = DotnetInventoryTest.class.getResourceAsStream("/inventory/zap-dotnet-commands.json")) {
            assertThat(in).isNotNull();
            JsonNode root = mapper.readTree(in);
            for (JsonNode command : root.path("commands")) {
                assertThat(registered)
                    .as("zap command %s must be a Condense leftover prefix", command.asText())
                    .contains(command.asText());
            }
        }

        assertThat(registered).contains(
            "dotnet test",
            "dotnet build",
            "dotnet format",
            "dotnet restore",
            "dotnet msbuild",
            "msbuild"
        );
    }
}
