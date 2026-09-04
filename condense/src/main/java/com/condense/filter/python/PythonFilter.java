package com.condense.filter.python;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.core.FilterStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilters({
    @CommandFilter("python -m pytest"),
    @CommandFilter("python3 -m pytest"),
    @CommandFilter("python -c"),
    @CommandFilter("python3 -c")
})
@ApplicationScoped
public class PythonFilter implements FilterStrategy {

    private final PytestFilter pytestFilter;

    public PythonFilter() {
        this(new PytestFilter());
    }

    @Inject
    public PythonFilter(@CommandFilter("pytest") PytestFilter pytestFilter) {
        this.pytestFilter = pytestFilter != null ? pytestFilter : new PytestFilter();
    }

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (command != null && command.contains("pytest")) {
            return pytestFilter.apply(command, result, config, verbose, ultraCompact);
        }
        return FilterResult.passthrough(result);
    }
}
