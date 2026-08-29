package com.condense.filter.pipeline;

import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An ordered sequence of {@link FilterStage} instances executed in series.
 *
 * <p>A pipeline receives input text, executes each stage sequentially, and returns
 * either intermediate transformed output or a final {@link FilterResult}.
 * If any stage returns a {@link StageResult#shortCircuit()} flag as {@code true},
 * execution stops immediately and the short-circuited output is returned.
 */
public class FilterPipeline {

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
     * @param input   the raw input text
     * @param context contextual metadata
     * @return the transformed output string
     */
    public String execute(String input, FilterContext context) {
        String current = input != null ? input : "";
        FilterContext ctx = context != null ? context : FilterContext.empty();

        for (FilterStage stage : stages) {
            StageResult stageResult = stage.process(current, ctx);
            if (stageResult == null) {
                continue;
            }
            current = stageResult.output() != null ? stageResult.output() : "";
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

    /**
     * Executes the pipeline on an {@link ExecutionResult} and wraps the final output
     * in a {@link FilterResult}.
     *
     * @param result  the raw command execution result
     * @param context contextual metadata
     * @return a FilterResult with token metrics
     */
    public FilterResult execute(ExecutionResult result, FilterContext context) {
        if (result == null) {
            return new FilterResult("", 0, 0, false);
        }
        String raw = result.readStdout().isBlank() && !result.readStderr().isBlank()
            ? result.readStderr()
            : result.readStdout();
        String output = execute(raw, context);
        return FilterResult.of(result, output);
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
