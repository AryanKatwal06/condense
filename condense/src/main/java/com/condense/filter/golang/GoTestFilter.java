package com.condense.filter.golang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.condense.annotation.CommandFilter;
import com.condense.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@CommandFilter("go test")
@ApplicationScoped
public class GoTestFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(GoTestFilter.class);
    private static final ObjectMapper MAPPER = com.condense.core.Mappers.JSON;

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.readStdout();
            List<String> failures = new ArrayList<>();
            int passed = 0, skipped = 0;

            for (String line : raw.lines().toList()) {
                if (line.isBlank() || !line.startsWith("{")) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String action = node.path("Action").asText();
                    String test   = node.path("Test").asText();
                    switch (action) {
                        case "pass"   -> { if (!test.isBlank()) passed++; }
                        case "skip"   -> { if (!test.isBlank()) skipped++; }
                        case "fail"   -> {
                            if (!test.isBlank()) failures.add("  FAIL: " + test);
                        }
                        default -> {} // "run", "output", "pause", "cont" — ignore
                    }
                } catch (Exception ignored) {
                    // Line was not JSON (normal text output before -json flag)
                }
            }

            // Fallback: no JSON events — parse plain text
            if (passed == 0 && failures.isEmpty()) {
                return parsePlainGoTest(result, raw, result.succeeded());
            }

            StringBuilder sb = new StringBuilder();
            if (!failures.isEmpty()) {
                sb.append("go test: ").append(failures.size()).append(" failure(s)\n");
                failures.forEach(f -> sb.append(f).append('\n'));
            }
            sb.append("passed: ").append(passed);
            if (skipped > 0) sb.append(" | skipped: ").append(skipped);
            if (!failures.isEmpty()) sb.append(" | failed: ").append(failures.size());

            return FilterResult.of(result, sb.toString().stripTrailing());

        } catch (Exception e) {
            log.warnf("GoTestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result);
        }
    }

    private FilterResult parsePlainGoTest(ExecutionResult result, String raw, boolean succeeded) {
        List<String> failures = raw.lines()
            .filter(l -> l.startsWith("--- FAIL:") || l.startsWith("FAIL"))
            .limit(20)
            .toList();

        if (failures.isEmpty()) {
            return FilterResult.passthrough(result); // Using passthrough as fallback if nothing is found
        }
        return FilterResult.passthrough(result); // Assuming parsePlainGoTest fallback should probably just passthrough if failed
    }
}