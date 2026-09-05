package com.condense.filter.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of {@link FilterPipeline#executeTraced}. {@link #output()} matches
 * {@link FilterPipeline#execute}.
 */
public record PipelineTrace(
    String output,
    List<StageTrace> stages,
    boolean shortCircuited
) {
    public PipelineTrace {
        output = output == null ? "" : output;
        stages = stages == null ? new ArrayList<>() : new ArrayList<>(stages);
    }
}
