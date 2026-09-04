package com.condense.filter.strategy;

import com.condense.core.Mappers;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Go test2json-style JSONL events. One JSON document per line.
 * {@link JsonStructureStrategy} is a single document at depth 6 and cannot do this.
 */
public final class JsonLinesStage implements FilterStage {

    public static final JsonLinesStage INSTANCE = new JsonLinesStage();
    private static final ObjectMapper MAPPER = Mappers.JSON;

    private JsonLinesStage() {}

    @Override
    public StageResult process(String input, FilterContext context) {
        String raw = input != null ? input : "";
        List<String> failures = new ArrayList<>();
        int passed = 0;
        int skipped = 0;

        for (String line : raw.lines().toList()) {
            if (line.isBlank() || !line.startsWith("{")) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(line);
                String action = node.path("Action").asText();
                String test = node.path("Test").asText();
                switch (action) {
                    case "pass" -> {
                        if (!test.isBlank()) {
                            passed++;
                        }
                    }
                    case "skip" -> {
                        if (!test.isBlank()) {
                            skipped++;
                        }
                    }
                    case "fail" -> {
                        if (!test.isBlank()) {
                            failures.add("  FAIL: " + test);
                        }
                    }
                    default -> {
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (passed == 0 && failures.isEmpty()) {
            return parsePlainGoTest(raw);
        }

        StringBuilder sb = new StringBuilder();
        if (!failures.isEmpty()) {
            sb.append("go test: ").append(failures.size()).append(" failure(s)\n");
            failures.forEach(f -> sb.append(f).append('\n'));
        }
        sb.append("passed: ").append(passed);
        if (skipped > 0) {
            sb.append(" | skipped: ").append(skipped);
        }
        if (!failures.isEmpty()) {
            sb.append(" | failed: ").append(failures.size());
        }
        return StageResult.continueWith(sb.toString().stripTrailing());
    }

    private static StageResult parsePlainGoTest(String raw) {
        List<String> failures = raw.lines()
            .filter(l -> l.startsWith("--- FAIL:") || l.startsWith("FAIL"))
            .limit(20)
            .toList();
        if (failures.isEmpty()) {
            return StageResult.continueWith(raw);
        }
        StringBuilder sb = new StringBuilder("go test: ")
            .append(failures.size()).append(" failure(s)\n");
        failures.forEach(f -> sb.append("  ").append(f).append('\n'));
        return StageResult.continueWith(sb.toString().stripTrailing());
    }
}
