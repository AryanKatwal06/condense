package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Compact {@code docker ps} table. Column heuristics are command-specific;
 * a generic tabular stage would be Phase 5 vocabulary.
 */
public final class DockerPsStage implements FilterStage {

    public static final DockerPsStage INSTANCE = new DockerPsStage();
    private static final Pattern COLUMNS_PATTERN = Pattern.compile("\\s{2,}");

    private DockerPsStage() {}

    @Override
    public StageResult process(String input, FilterContext context) {
        String raw = input != null ? input : "";
        List<String> lines = raw.lines().toList();
        if (lines.size() <= 1) {
            return StageResult.continueWith("(no containers running)");
        }

        List<String> compact = new ArrayList<>();
        compact.add("ID       IMAGE                STATUS    NAME");
        compact.add("─".repeat(55));

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] cols = COLUMNS_PATTERN.split(line);
            if (cols.length < 7) {
                compact.add(line.trim());
                continue;
            }
            String id = cols[0].length() > 8 ? cols[0].substring(0, 8) : cols[0];
            String image = cols[1].length() > 20 ? cols[1].substring(0, 19) + "…" : cols[1];
            String status = cols[4].length() > 10 ? cols[4].substring(0, 10) : cols[4];
            String name = cols[cols.length - 1];
            compact.add(String.format("%-8s %-20s %-10s %s", id, image, status, name));
        }
        return StageResult.continueWith(String.join("\n", compact));
    }
}
