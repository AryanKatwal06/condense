package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.HeadTailStage;
import com.condense.filter.strategy.JsonStructureStrategy;

public final class CatContentStage implements FilterStage {
    public static final CatContentStage INSTANCE = new CatContentStage();
    private static final HeadTailStage HEAD_TAIL = new HeadTailStage(20, 20);

    private CatContentStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        java.util.List<String> lines = raw.lines().toList();
        if (!lines.isEmpty()) {
            String trimmed = lines.get(0).trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    String skeleton = JsonStructureStrategy.skeleton(raw.trim());
                    return StageResult.continueWith(skeleton);
                } catch (Exception ignored) {
                }
            }
        }
        if (lines.size() > 40) {
            return HEAD_TAIL.process(raw, context);
        }
        return StageResult.continueWith(raw);
    }
}
