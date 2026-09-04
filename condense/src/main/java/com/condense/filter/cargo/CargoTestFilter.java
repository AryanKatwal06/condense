package com.condense.filter.cargo;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilter("cargo test")
@ApplicationScoped
public class CargoTestFilter extends PipelineBackedFilter {

    public CargoTestFilter() {
        super();
    }

    @Inject
    public CargoTestFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        try {
            long sizeOut = java.nio.file.Files.size(result.stdoutFile());
            long sizeErr = java.nio.file.Files.size(result.stderrFile());
            if (sizeOut == 0 && sizeErr > 0) {
                return result.readStderr();
            }
            return result.readStdout();
        } catch (Exception e) {
            return result.readStdout();
        }
    }

    @Override
    protected String definitionName() {
        return "cargo-test";
    }
}
