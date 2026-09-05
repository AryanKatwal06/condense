package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.config.BuiltinDefinition;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import com.condense.filter.pipeline.config.FilterOverrideLoader;

import java.util.Locale;
import java.util.Objects;

/**
 * Hosts a leftover builtin definition that has no {@code @CommandFilter} Java class.
 *
 * <p>Not a CDI bean. {@code StrategyRegistry} constructs one instance per leftover
 * definition and registers that definition's {@code commands}.
 */
public final class CatalogBackedFilter extends PipelineBackedFilter {

    private final BuiltinDefinition definition;

    public CatalogBackedFilter(String definitionName) {
        this(definitionName, FilterOverrideLoader.standalone());
    }

    public CatalogBackedFilter(String definitionName, FilterOverrideLoader overrideLoader) {
        super(overrideLoader, Objects.requireNonNull(definitionName, "definitionName"));
        this.definition = BuiltinDefinitionCatalog.standalone().requiredDefinition(definitionName);
    }

    @Override
    protected FilterResult beforePipeline(
            String command,
            ExecutionResult result,
            CondenseConfig config,
            int verbose,
            boolean ultraCompact) {
        BuiltinDefinition.Gate gate = definition.gate();
        if (gate == null) {
            return null;
        }
        if (gate.passthroughOnNonzeroExit() && result != null && !result.succeeded()) {
            return FilterResult.passthrough(result);
        }
        if (gate.passthroughVerbose() != null && verbose >= gate.passthroughVerbose()) {
            return FilterResult.passthrough(result);
        }
        if (gate.passthroughMaxLines() != null) {
            String selected = selectInput(command, result, config, verbose, ultraCompact);
            long lines = selected.lines().filter(line -> !line.isBlank()).count();
            if (lines <= gate.passthroughMaxLines()) {
                return FilterResult.passthrough(result);
            }
        }
        return null;
    }

    @Override
    protected String selectInput(
            String command,
            ExecutionResult result,
            CondenseConfig config,
            int verbose,
            boolean ultraCompact) {
        String mode = definition.selectInput();
        String key = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (key.isBlank() || BuiltinDefinition.SELECT_STDOUT_OR_STDERR.equals(key)) {
            return super.selectInput(command, result, config, verbose, ultraCompact);
        }
        return switch (key) {
            case BuiltinDefinition.SELECT_STDERR_THEN_STDOUT -> stderrThenStdout(result);
            case BuiltinDefinition.SELECT_STDOUT -> result.readStdout();
            case BuiltinDefinition.SELECT_STDERR -> result.readStderr();
            default -> super.selectInput(command, result, config, verbose, ultraCompact);
        };
    }
}
