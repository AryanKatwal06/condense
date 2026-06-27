package com.zapproxy.filter.python;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
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
                              ZapConfig config, int verbose, boolean ultraCompact) {
        // Delegate to pytest filter for pytest invocations
        if (command.contains("pytest")) {
            return pytestFilter.apply(command, result, config, verbose, ultraCompact);
        }
        // For python -c: passthrough (output is intentional)
        return FilterResult.passthrough(result.stdout());
    }
}