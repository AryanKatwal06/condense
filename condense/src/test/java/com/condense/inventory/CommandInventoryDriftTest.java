package com.condense.inventory;

import com.condense.filter.pipeline.config.BuiltinDefinition;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandInventoryDriftTest {

    @Test
    void generatedMarkdownMatchesPinAndCatalog() throws Exception {
        String expected = renderMarkdown();
        Path committed = findCommitted();
        assertThat(committed).exists();
        String actual = Files.readString(committed, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(actual)
            .as("docs/generated/command-inventory.md is stale. Re-run CommandInventoryDriftTest after updating the pin")
            .isEqualTo(expected);
    }

    static String renderMarkdown() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try (var in = CommandInventoryDriftTest.class.getResourceAsStream("/inventory/zap-families.json")) {
            root = mapper.readTree(in);
        }
        BuiltinDefinitionCatalog catalog = BuiltinDefinitionCatalog.standalone();
        StringBuilder out = new StringBuilder();
        out.append("# Command inventory\n\n");
        out.append("Generated from the pinned zap family list and Condense leftover catalog. ");
        out.append("Do not edit by hand.\n\n");
        out.append("Pin: `").append(root.path("zap_commit").asText()).append("`\n\n");
        out.append("| Family | Zap source | Condense definition | Commands |\n");
        out.append("|---|---|---|---|\n");
        for (JsonNode family : root.path("families")) {
            String id = family.path("id").asText();
            String source = family.path("zap_source").asText();
            String definition;
            if (family.hasNonNull("exclusion")) {
                definition = "excluded (" + family.path("exclusion").path("kind").asText() + ")";
            } else {
                String name = family.path("condense_definition").asText();
                BuiltinDefinition def = catalog.requiredDefinition(name);
                definition = "`" + def.name() + "`";
            }
            List<String> commands = new ArrayList<>();
            family.path("zap_commands").forEach(node -> commands.add("`" + node.asText() + "`"));
            out.append("| `").append(id).append("` | `").append(source).append("` | ")
                .append(definition).append(" | ")
                .append(commands.isEmpty() ? "—" : String.join(", ", commands))
                .append(" |\n");
        }
        return out.toString();
    }

    static Path findCommitted() {
        Path cwd = Path.of("").toAbsolutePath();
        Path fromRoot = cwd.resolve("docs/generated/command-inventory.md");
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        Path fromModule = cwd.resolve("../docs/generated/command-inventory.md").normalize();
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        return fromRoot;
    }
}
