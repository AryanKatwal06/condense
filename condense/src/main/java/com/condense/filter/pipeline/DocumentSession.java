package com.condense.filter.pipeline;

import java.util.Objects;

/**
 * Default session for stages that have no incremental implementation.
 * Buffers input and calls {@link FilterStage#process} once at the end.
 */
public final class DocumentSession implements StageSession {

    private final FilterStage stage;
    private final StringBuilder fed = new StringBuilder();
    private boolean hasFeed;
    private boolean finished;

    public DocumentSession(FilterStage stage) {
        this.stage = Objects.requireNonNull(stage, "stage");
    }

    @Override
    public void acceptDocument(String text, EmissionSink sink, FilterContext context) {
        finish(text != null ? text : "", sink, context);
    }

    @Override
    public void feedLine(String line, EmissionSink sink, FilterContext context) {
        if (hasFeed) {
            fed.append('\n');
        }
        fed.append(line != null ? line : "");
        hasFeed = true;
    }

    @Override
    public void endOfInput(EmissionSink sink, FilterContext context) {
        finish(hasFeed ? fed.toString() : "", sink, context);
    }

    private void finish(String input, EmissionSink sink, FilterContext context) {
        if (finished) {
            return;
        }
        finished = true;
        FilterContext ctx = context != null ? context : FilterContext.empty();
        StageResult result = stage.process(input, ctx);
        if (sink == null) {
            return;
        }
        if (result == null) {
            sink.emitDocument(input);
            return;
        }
        sink.emitDocument(result.output());
        if (result.shortCircuit()) {
            sink.shortCircuit();
        }
    }
}
