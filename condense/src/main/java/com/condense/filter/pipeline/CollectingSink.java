package com.condense.filter.pipeline;

/**
 * In-memory sink used to chain sessions and to compare incremental replay
 * against batch {@link FilterPipeline#execute}.
 */
final class CollectingSink implements EmissionSink {

    private final StringBuilder buf = new StringBuilder();
    private boolean any;
    private boolean shortCircuited;

    @Override
    public void emit(String line) {
        String value = line != null ? line : "";
        if (any) {
            buf.append('\n');
        }
        buf.append(value);
        any = true;
    }

    @Override
    public void emitDocument(String text) {
        String value = text != null ? text : "";
        if (!any) {
            buf.append(value);
            any = true;
            return;
        }
        if (!value.isEmpty()) {
            buf.append('\n').append(value);
        }
    }

    @Override
    public void shortCircuit() {
        shortCircuited = true;
    }

    @Override
    public boolean isShortCircuited() {
        return shortCircuited;
    }

    String output() {
        return buf.toString();
    }
}
