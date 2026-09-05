package com.condense.filter.pipeline;

/**
 * Irrevocable output from a {@link StageSession}.
 *
 * <p>Once a line or document is emitted it cannot be edited. Stages that would
 * rewrite earlier text must declare {@link Streamability#DOCUMENT}.
 */
public interface EmissionSink {

    /** Emit one completed line, without a trailing newline. */
    void emit(String line);

    /**
     * Emit a whole-document transformation as a single blob.
     * Used by {@link DocumentSession} so batch {@code process()} output is preserved byte-for-byte.
     */
    void emitDocument(String text);

    /** Stop feeding later stages after this emission. */
    void shortCircuit();

    boolean isShortCircuited();
}
