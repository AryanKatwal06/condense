package com.condense.filter.pipeline;

/**
 * Per-invocation incremental state for a {@link FilterStage}.
 *
 * <p>Stages themselves stay stateless. All buffers live here and are discarded
 * after {@link #endOfInput}.
 */
public interface StageSession {

    void feedLine(String line, EmissionSink sink, FilterContext context);

    void endOfInput(EmissionSink sink, FilterContext context);

    /**
     * Default line-walk. {@link DocumentSession} overrides this to pass the
     * original string to {@link FilterStage#process} unchanged.
     */
    default void acceptDocument(String text, EmissionSink sink, FilterContext context) {
        String raw = text == null ? "" : text;
        if (raw.isEmpty()) {
            endOfInput(sink, context);
            return;
        }
        for (String line : LineDiff.split(raw)) {
            feedLine(line, sink, context);
            if (sink.isShortCircuited()) {
                return;
            }
        }
        endOfInput(sink, context);
    }
}
