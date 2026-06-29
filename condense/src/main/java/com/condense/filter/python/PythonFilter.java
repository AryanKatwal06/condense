package com.condense.filter.python;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.*;
import jakarta.enterprise.context.ApplicationScoped;

@CommandFilters({
    @CommandFilter("python -m pytest"),
    @CommandFilter("python3 -m pytest"),
    @CommandFilter("python -c"),
    @CommandFilter("python3 -c")
})
@ApplicationScoped
public class PythonFilter implements FilterStrategy {

    private final PytestFilter pytestFilter = new PytestFilter();

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        // Delegate to pytest filter for pytest invocations
        if (command.contains("pytest")) {
            return pytestFilter.apply(command, result, config, verbose, ultraCompact);
        }
        // For python -c: passthrough (output is intentional)
        return FilterResult.passthrough(result);
    }
}