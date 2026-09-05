package com.condense.filter.pipeline;

/**
 * How a {@link FilterStage} may emit relative to input arrival.
 *
 * <p>Declared on the Java stage class only. Declarative TOML cannot override this.
 */
public enum Streamability {
    /** A completed input line can be dropped or emitted; never retracts a prior emission. */
    ORDER_LOCAL,
    /** May hold a bounded prefix and emit it before EOF; never retracts. */
    WINDOWED,
    /** Incremental internally, but the first emission is at end of input. */
    FINALIZE_ONLY,
    /** Needs the whole body, or rewrites an earlier emission. */
    DOCUMENT
}
