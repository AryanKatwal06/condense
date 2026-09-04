package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

import java.util.List;

/**
 * Keeps the last {@code maxLines} lines and prefixes a truncation notice.
 */
public final class TailLinesStage implements FilterStage {

    private final int maxLines;
    private final boolean skipBlank;
    private final boolean headerOnlyWhenTruncating;

    public TailLinesStage(int maxLines) {
        this(maxLines, false, true);
    }

    public TailLinesStage(int maxLines, boolean skipBlank, boolean headerOnlyWhenTruncating) {
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines must be >= 1");
        }
        this.maxLines = maxLines;
        this.skipBlank = skipBlank;
        this.headerOnlyWhenTruncating = headerOnlyWhenTruncating;
    }

    @Override
    public StageResult process(String input, FilterContext context) {
        String text = input != null ? input : "";
        List<String> lines = text.lines()
            .filter(l -> !skipBlank || !l.isBlank())
            .toList();
        if (lines.size() <= maxLines) {
            if (headerOnlyWhenTruncating) {
                return StageResult.continueWith(String.join("\n", lines));
            }
        }
        int from = Math.max(0, lines.size() - maxLines);
        List<String> tail = lines.subList(from, lines.size());
        String header = "... (showing last " + maxLines + " of " + lines.size() + " lines)\n";
        return StageResult.continueWith(header + String.join("\n", tail));
    }
}
