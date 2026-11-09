package com.condense.filter.pipeline;

import com.condense.core.TokenCounter;

/**
 * Rejects a compression step that makes the output larger than its input.
 *
 * <p>Condense exists to save tokens. A pipeline that adds headers, summaries,
 * or annotations to an already tiny capture can therefore be a net loss. The
 * gate only fires when the filtered text is strictly longer in characters and
 * in estimated tokens than the raw input.
 */
public final class NetWinGate {

    private NetWinGate() {}

    /**
     * @param raw          the original command output
     * @param filtered     the pipeline output, before provenance stamping
     * @param ultraCompact when true, additive enrichment is an explicit request
     * @return true when the filtered output is larger on both metrics
     */
    public static boolean isNetLoss(String raw, String filtered, boolean ultraCompact) {
        if (raw == null || raw.isEmpty() || filtered == null) {
            return false;
        }
        if (ultraCompact) {
            return false;
        }
        if (filtered.length() <= raw.length()) {
            return false;
        }
        return TokenCounter.count(filtered) > TokenCounter.count(raw);
    }
}
