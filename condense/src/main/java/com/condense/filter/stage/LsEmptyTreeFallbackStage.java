package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

public final class LsEmptyTreeFallbackStage implements FilterStage {
    public static final LsEmptyTreeFallbackStage INSTANCE = new LsEmptyTreeFallbackStage();

    private LsEmptyTreeFallbackStage() {}

    @Override
    public StageResult process(String tree, FilterContext ctx) {
        if (tree.isBlank()) {
            long count = 0;
            if (ctx.result() != null) {
                try (java.util.stream.Stream<String> stream = ctx.result().stdoutLines()) {
                    count = stream.filter(l -> !l.isBlank()).count();
                } catch (Exception ignored) {
                }
            }
            return StageResult.continueWith(count + " items");
        }
        return StageResult.continueWith(tree);
    }
}
