package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Contextual metadata provided to {@link FilterStage} instances during pipeline execution.
 *
 * @param command      the command being filtered (e.g. "npm install")
 * @param result       the raw ExecutionResult, if available (may be null in standalone stage tests)
 * @param config       the Condense configuration
 * @param verbose      verbosity level (0=compact, 1=normal, 2=verbose, 3=maximum)
 * @param ultraCompact whether ultra-compact mode is enabled
 * @param incidents    mutable per-execute list of fail-open events; never shared across calls
 */
public record FilterContext(
    String command,
    ExecutionResult result,
    CondenseConfig config,
    int verbose,
    boolean ultraCompact,
    List<FilterIncident> incidents
) {
    public FilterContext {
        command = command != null ? command : "";
        config = config != null ? config : CondenseConfig.defaults();
        if (incidents == null) {
            incidents = new ArrayList<>();
        }
    }

    /**
     * Compatibility constructor used by existing tests and reflective checks.
     */
    public FilterContext(
            String command,
            ExecutionResult result,
            CondenseConfig config,
            int verbose,
            boolean ultraCompact) {
        this(command, result, config, verbose, ultraCompact, new ArrayList<>());
    }

    public static FilterContext empty() {
        return new FilterContext("", null, CondenseConfig.defaults(), 0, false, new ArrayList<>());
    }

    public static FilterContext of(String command, ExecutionResult result, CondenseConfig config, int verbose, boolean ultraCompact) {
        return new FilterContext(
            command != null ? command : "",
            result,
            config != null ? config : CondenseConfig.defaults(),
            verbose,
            ultraCompact,
            new ArrayList<>()
        );
    }

    public void recordIncident(FilterIncident incident) {
        if (incident == null || incidents == null) {
            return;
        }
        try {
            incidents.add(incident);
        } catch (UnsupportedOperationException ignored) {
            // Immutable list supplied by a test; fail-open.
        }
    }
}
