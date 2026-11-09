package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Preserves discovered paths and appends a compact extension breakdown. */
@DeclarativeStage(aliases = {"find_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class FindSummaryStage implements FilterStage {

    public static final FindSummaryStage INSTANCE = new FindSummaryStage();
    static final int SMALL_RESULT_PATHS = 40;
    static final int MAX_VISIBLE_PATHS = 100;

    private FindSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        List<String> paths = (raw != null ? raw : "").lines()
            .map(String::strip)
            .filter(path -> !path.isEmpty())
            .toList();
        if (paths.isEmpty() || paths.size() <= SMALL_RESULT_PATHS) {
            return StageResult.continueWith(raw != null ? raw : "");
        }

        Map<String, Integer> extensions = new LinkedHashMap<>();
        for (String path : paths) {
            String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) {
                extensions.merge(name.substring(dot), 1, Integer::sum);
            }
        }

        StringBuilder output = new StringBuilder();
        int visible = Math.min(paths.size(), MAX_VISIBLE_PATHS);
        Map<String, List<String>> visibleByDirectory = new LinkedHashMap<>();
        for (int i = 0; i < visible; i++) {
            String path = paths.get(i);
            int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String directory = separator < 0 ? "" : path.substring(0, separator);
            String name = separator < 0 ? path : path.substring(separator + 1);
            visibleByDirectory.computeIfAbsent(directory, ignored -> new java.util.ArrayList<>()).add(name);
        }
        for (Map.Entry<String, List<String>> entry : visibleByDirectory.entrySet()) {
            if (entry.getKey().isEmpty()) {
                entry.getValue().forEach(name -> output.append(name).append('\n'));
            } else {
                output.append(entry.getKey()).append("/: ")
                    .append(String.join(", ", entry.getValue())).append('\n');
            }
        }
        if (paths.size() > visible) {
            output.append("... (").append(paths.size() - visible)
                .append(" paths omitted) ...\n");
        }
        output.append(paths.size()).append(" result(s)");
        if (!extensions.isEmpty()) {
            output.append(" — ");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : extensions.entrySet()) {
                if (!first) {
                    output.append(", ");
                }
                output.append(entry.getKey()).append(": ").append(entry.getValue());
                first = false;
            }
        }
        return StageResult.continueWith(output.toString());
    }
}
