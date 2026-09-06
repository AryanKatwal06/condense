package com.condense.filter.pipeline.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders {@code META-INF/condense/stage-inventory.json} to markdown.
 * Invoked from Maven {@code process-classes} to fail on committed-doc drift.
 */
public final class StageInventoryWriter {

    public static final String RESOURCE = "/META-INF/condense/stage-inventory.json";

    private StageInventoryWriter() {}

    public static void main(String[] args) throws Exception {
        String expected = renderMarkdown(readInventory());
        Path committed = findCommitted();
        if (!Files.isRegularFile(committed)) {
            System.err.println("Missing " + committed.toAbsolutePath());
            System.exit(1);
        }
        String actual = Files.readString(committed, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!expected.equals(actual)) {
            System.err.println("docs/generated/stage-inventory.md is stale. Expected:");
            System.err.println(expected);
            System.exit(1);
        }
        System.out.println("Stage inventory markdown matches generated resource");
    }

    public static String renderMarkdown(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        StringBuilder out = new StringBuilder();
        out.append("# Stage inventory\n\n");
        out.append("Generated from `@DeclarativeStage`. Do not edit by hand.\n\n");
        out.append("| Canonical | Aliases | Capability | Class |\n");
        out.append("|---|---|---|---|\n");
        for (JsonNode stage : root.path("stages")) {
            List<String> aliases = new ArrayList<>();
            stage.path("aliases").forEach(node -> aliases.add("`" + node.asText() + "`"));
            out.append("| `").append(stage.path("canonical").asText()).append("` | ")
                .append(String.join(", ", aliases)).append(" | ")
                .append(stage.path("capability").asText()).append(" | `")
                .append(stage.path("className").asText()).append("` |\n");
        }
        return out.toString();
    }

    public static String readInventory() throws Exception {
        try (InputStream in = StageInventoryWriter.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Path findCommitted() {
        Path nested = Path.of("docs", "generated", "stage-inventory.md");
        if (Files.isRegularFile(nested)) {
            return nested;
        }
        Path fromModule = Path.of("..").resolve(nested);
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        return Path.of("condense").resolve("..").resolve(nested).normalize();
    }
}
