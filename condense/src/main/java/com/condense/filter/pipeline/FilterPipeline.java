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
        String current = input != null ? input : "";
        FilterContext ctx = context != null ? context : FilterContext.empty();

        for (FilterStage stage : stages) {
            StageResult stageResult;
            try {
                stageResult = stage.process(current, ctx);
            } catch (Exception e) {
                log.warnf("Stage %s threw an exception during pipeline execution: %s",
                    stage.getClass().getName(), e.getMessage());
                continue;
            }
            if (stageResult == null) {
                continue;
            }
            current = stageResult.output();
            if (stageResult.shortCircuit()) {
                break;
            }
        }
        return current;
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
