package com.condense.filter.pipeline;

import java.util.Objects;

/**
 * Attaches a canonical declarative alias to a {@link FilterStage} without
 * changing how {@link #process} runs.
 */
public final class NamedStage implements FilterStage {

    private final String id;
    private final FilterStage delegate;

    private NamedStage(String id, FilterStage delegate) {
        this.id = Objects.requireNonNull(id, "id");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static FilterStage wrap(String id, FilterStage delegate) {
        if (delegate == null) {
            return null;
        }
        String resolved = id == null || id.isBlank() ? delegate.stageId() : id;
        if (delegate instanceof NamedStage named && named.id.equals(resolved)) {
            return named;
        }
        return new NamedStage(resolved, delegate);
    }

    public static FilterStage unwrap(FilterStage stage) {
        return stage instanceof NamedStage named ? named.delegate : stage;
    }

    public FilterStage delegate() {
        return delegate;
    }

    @Override
    public StageResult process(String input, FilterContext context) {
        return delegate.process(input, context);
    }

    @Override
    public String stageId() {
        return id;
    }
}
