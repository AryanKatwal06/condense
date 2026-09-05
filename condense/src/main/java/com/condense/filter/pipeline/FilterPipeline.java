package com.condense.filter.pipeline;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An ordered sequence of {@link FilterStage} instances executed in series.
 *
 * <p>A pipeline receives input text, executes each stage sequentially, and returns
 * the final transformed output string.
 * If any stage returns a {@link StageResult#shortCircuit()} flag as {@code true},
 * execution stops immediately and the short-circuited output is returned.
 *
 * <p>The pipeline operates with a fail-open philosophy: if any stage throws an unchecked
 * exception during {@link FilterStage#process}, the error is logged as a warning and the
 * pipeline continues with the previous stage's output.
 */
public class FilterPipeline {

    private static final Logger log = Logger.getLogger(FilterPipeline.class);

    private final List<FilterStage> stages;

    public FilterPipeline(List<FilterStage> stages) {
        this.stages = List.copyOf(Objects.requireNonNull(stages, "stages must not be null"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static FilterPipeline of(FilterStage... stages) {
        return new FilterPipeline(List.of(stages));
    }

    public List<FilterStage> stages() {
        return stages;
    }

    public int size() {
        return stages.size();
    }

    public boolean isEmpty() {
        return stages.isEmpty();
    }

    /**
     * Live-print capability derived from every stage's {@link FilterStage#streamability()}.
     * Empty pipelines are identity and therefore {@link PipelineMode#STREAM}.
     */
    public PipelineMode mode() {
        if (stages.isEmpty()) {
            return PipelineMode.STREAM;
        }
        for (FilterStage stage : stages) {
            Streamability streamability = stage.streamability();
            if (streamability == Streamability.DOCUMENT
                || streamability == Streamability.FINALIZE_ONLY) {
                return PipelineMode.CAPTURE;
            }
        }
        return PipelineMode.STREAM;
    }

    /**
     * Incremental replay of the same stages. Until stages grow real sessions this
     * is {@link DocumentSession} wrapping {@link FilterStage#process}, so output
     * must match {@link #execute}.
     */
    public String executeIncremental(String input, FilterContext context) {
        return walkSessions(input, context).output();
    }

    /**
     * Executes all stages in sequence on the given input text.
     *
     * <p>If any stage throws an exception, it is caught, logged, and execution continues
     * with the current intermediate text (fail-open).
     *
     * @param input   the raw input text
     * @param context contextual metadata
     * @return the transformed output string
     */
    public String execute(String input, FilterContext context) {
        return walk(input, context, false, 0).output();
    }

    /**
     * Same walk as {@link #execute} plus per-stage line and token accounting.
     */
    public PipelineTrace executeTraced(String input, FilterContext context) {
        return executeTraced(input, context, Integer.MAX_VALUE);
    }

    /**
     * @param sampleLimit maximum dropped/added lines stored per stage; {@code 0} keeps counts only
     */
    public PipelineTrace executeTraced(String input, FilterContext context, int sampleLimit) {
        return walk(input, context, true, Math.max(0, sampleLimit));
    }

    /**
     * Executes all stages in sequence on the given input text with empty context.
     *
     * @param input the raw input text
     * @return the transformed output string
     */
    public String execute(String input) {
        return execute(input, FilterContext.empty());
    }

    private PipelineTrace walk(String input, FilterContext context, boolean trace, int sampleLimit) {
        String current = input != null ? input : "";
        FilterContext ctx = context != null ? context : FilterContext.empty();
        List<StageTrace> traces = trace ? new ArrayList<>() : null;
        boolean skipping = false;
        boolean shortCircuited = false;

        for (FilterStage stage : stages) {
            String id = stage.stageId();
            if (skipping) {
                if (traces != null) {
                    traces.add(StageTrace.skipped(id));
                }
                continue;
            }

            String stageInput = current;
            StageResult stageResult;
            try {
                stageResult = stage.process(current, ctx);
            } catch (Exception e) {
                log.warnf("Stage %s threw an exception during pipeline execution: %s",
                    id, e.getMessage());
                ctx.recordIncident(FilterIncident.stageException(id, e.getMessage()));
                if (traces != null) {
                    traces.add(StageTrace.of(
                        id, StageTrace.EXCEPTION, stageInput, current, false, e.getMessage(), sampleLimit));
                }
                continue;
            }
            if (stageResult == null) {
                if (traces != null) {
                    traces.add(StageTrace.of(
                        id, StageTrace.RAN, stageInput, current, false, "null_result", sampleLimit));
                }
                continue;
            }
            current = stageResult.output();
            if (stageResult.shortCircuit()) {
                shortCircuited = true;
                skipping = true;
            }
            if (traces != null) {
                traces.add(StageTrace.of(
                    id,
                    shortCircuited ? StageTrace.SHORT_CIRCUITED : StageTrace.RAN,
                    stageInput,
                    current,
                    stageResult.shortCircuit(),
                    null,
                    sampleLimit
                ));
            }
        }
        return new PipelineTrace(current, traces == null ? List.of() : traces, shortCircuited);
    }

    private CollectingSink walkSessions(String input, FilterContext context) {
        String current = input != null ? input : "";
        FilterContext ctx = context != null ? context : FilterContext.empty();
        CollectingSink last = new CollectingSink();
        last.emitDocument(current);
        boolean skipping = false;

        for (FilterStage stage : stages) {
            if (skipping) {
                continue;
            }
            CollectingSink sink = new CollectingSink();
            try {
                StageSession session = stage.openSession();
                session.acceptDocument(current, sink, ctx);
            } catch (Exception e) {
                log.warnf("Stage %s threw an exception during pipeline execution: %s",
                    stage.stageId(), e.getMessage());
                ctx.recordIncident(FilterIncident.stageException(stage.stageId(), e.getMessage()));
                continue;
            }
            current = sink.output();
            last = sink;
            if (sink.isShortCircuited()) {
                skipping = true;
            }
        }
        return last;
    }

    public static final class Builder {
        private final List<FilterStage> stages = new ArrayList<>();

        public Builder addStage(FilterStage stage) {
            stages.add(Objects.requireNonNull(stage, "stage must not be null"));
            return this;
        }

        public Builder addStages(FilterStage... stages) {
            for (FilterStage s : stages) {
                addStage(s);
            }
            return this;
        }

        public FilterPipeline build() {
            return new FilterPipeline(stages);
        }
    }
}
