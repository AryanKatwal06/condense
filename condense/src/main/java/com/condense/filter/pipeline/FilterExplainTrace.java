package com.condense.filter.pipeline;

import com.condense.core.FilterResult;
import com.condense.filter.pipeline.config.PipelineDecision;

/**
 * Diagnostic snapshot of one {@link PipelineBackedFilter} invocation.
 * Production {@link PipelineBackedFilter#apply} does not use this type.
 */
public record FilterExplainTrace(
    FilterResult result,
    boolean gateFired,
    String gateKind,
    String gateDetail,
    PipelineDecision decision,
    PipelineTrace pipelineTrace,
    String filterName,
    String definitionName,
    String selectedInput,
    boolean applyFallback
) {}
