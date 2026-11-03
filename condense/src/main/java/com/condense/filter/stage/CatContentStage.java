package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.HeadTailStage;
import com.condense.filter.strategy.JsonStructureStrategy;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Compresses large plain-text files with a head/tail window.
 *
 * <p>JSON is only reduced to a structural skeleton in ultra-compact mode or for
 * payloads above {@link #JSON_SKELETON_BYTES}. A normal {@code cat package.json}
 * stays byte-for-byte intact because reading the manifest is the whole point.
 */
@DeclarativeStage(aliases = {"cat_content"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class CatContentStage implements FilterStage {
    public static final CatContentStage INSTANCE = new CatContentStage();

    static final int MAX_LINES_WITHOUT_COMPRESSION = 200;
    static final int JSON_SKELETON_BYTES = 32 * 1024;
    static final int HEAD_LINES = 120;
    static final int TAIL_LINES = 60;

    private static final HeadTailStage HEAD_TAIL = new HeadTailStage(HEAD_LINES, TAIL_LINES);

    private CatContentStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        String text = raw != null ? raw : "";
        List<String> lines = text.lines().toList();
        if (!lines.isEmpty()) {
            String trimmed = lines.get(0).trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return json(text, context);
            }
        }
        if (lines.size() > MAX_LINES_WITHOUT_COMPRESSION) {
            return HEAD_TAIL.process(text, context);
        }
        return StageResult.continueWith(text);
    }

    private StageResult json(String text, FilterContext context) {
        boolean ultraCompact = context != null && context.ultraCompact();
        int rawBytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (!ultraCompact && rawBytes <= JSON_SKELETON_BYTES) {
            return StageResult.continueWith(text);
        }
        String trimmed = text.trim();
        String skeleton;
        try {
            skeleton = JsonStructureStrategy.skeleton(trimmed);
        } catch (Exception ignored) {
            return StageResult.continueWith(text);
        }
        if (skeleton.equals(trimmed)) {
            return StageResult.continueWith(text);
        }
        int skeletonBytes = skeleton.getBytes(StandardCharsets.UTF_8).length;
        int omitted = Math.max(0, rawBytes - skeletonBytes);
        return StageResult.continueWith(
            "... (JSON skeleton, " + omitted + " bytes omitted) ...\n" + skeleton);
    }
}
