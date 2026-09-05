package com.condense.filter.pipeline;

/**
 * Derived live-print capability of a {@link FilterPipeline}.
 *
 * <p>Computed from {@link FilterStage#streamability()} — never stored in TOML.
 */
public enum PipelineMode {
    /** Every stage is {@link Streamability#ORDER_LOCAL} or {@link Streamability#WINDOWED}. */
    STREAM,
    /** Any stage is {@link Streamability#DOCUMENT} or {@link Streamability#FINALIZE_ONLY}. */
    CAPTURE
}
