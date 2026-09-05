package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;

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
            Document.ResourceDocument payload = new Document.ResourceDocument(List.of(), true);
            publish(context, payload);
            return StageResult.continueWith(TextRenderer.renderResource(payload));
        }

        List<Document.ResourceRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] cols = COLUMNS_PATTERN.split(line);
            if (cols.length < 7) {
                rows.add(new Document.ResourceRow("", "", "", "", line.trim()));
                continue;
            }
            String id = cols[0].length() > 8 ? cols[0].substring(0, 8) : cols[0];
            String image = cols[1].length() > 20 ? cols[1].substring(0, 19) + "…" : cols[1];
            String status = cols[4].length() > 10 ? cols[4].substring(0, 10) : cols[4];
            String name = cols[cols.length - 1];
            rows.add(new Document.ResourceRow(id, image, status, name, ""));
        }
        Document.ResourceDocument payload = new Document.ResourceDocument(rows, false);
        publish(context, payload);
        return StageResult.continueWith(TextRenderer.renderResource(payload));
    }

    private static void publish(FilterContext context, Document.ResourceDocument payload) {
        if (context != null && context.documentBuilder() != null) {
            context.documentBuilder().resource(payload);
        }
    }
}
