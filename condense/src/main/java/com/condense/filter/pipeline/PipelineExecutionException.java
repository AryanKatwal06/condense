package com.condense.filter.pipeline;

/**
 * Thrown when a filter stage in a {@link FilterPipeline} throws an exception or
 * unrecoverable error.
 *
 * <p>Under Condense's fail-open design (Option A), a stage failure compromises the
 * transformation invariant. Rather than continuing with stale or partially-filtered
 * text, the pipeline aborts immediately so the filter layer can trigger full fallback
 * to raw output passthrough.
 */
public class PipelineExecutionException extends RuntimeException {

    private final String stageId;
    private final String stageInput;

    public PipelineExecutionException(String stageId, String message, String stageInput, Throwable cause) {
        super("Stage " + stageId + " failed: " + message, cause);
        this.stageId = stageId;
        this.stageInput = stageInput;
    }

    public String stageId() {
        return stageId;
    }

    public String stageInput() {
        return stageInput;
    }
}
