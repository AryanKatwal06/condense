package com.condense;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import picocli.CommandLine;

@QuarkusMain
public class CondenseMain implements QuarkusApplication {

    @Inject
    CommandLine.IFactory factory;

    @Inject
    @io.quarkus.picocli.runtime.annotations.TopCommand
    CondenseRootCommand rootCommand;

    @Override
    public int run(String... args) {
        CommandLine cmd = new CommandLine(rootCommand, factory)
            .setExecutionExceptionHandler((ex, c, parseResult) -> {
                c.getErr().println("condense: error: " + ex.getMessage());
                return 1;
            })
            .setCaseInsensitiveEnumValuesAllowed(true)
            .setUnmatchedArgumentsAllowed(true)
            .setStopAtPositional(true);

        int exitCode = cmd.execute(args);

        // If the root command stored a passthrough exit code, use that instead
        Object result = cmd.getExecutionResult();
        if (result instanceof Integer passthroughExit) {
            return passthroughExit;
        }
        return exitCode;
    }
}
