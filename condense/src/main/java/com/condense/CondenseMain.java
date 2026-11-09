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
        CliPreParser.PreParseResult preParsed = CliPreParser.parse(args);

        CommandLine cmd = new CommandLine(rootCommand, factory)
            .setExecutionExceptionHandler((ex, c, parseResult) -> {
                c.getErr().println("condense: error: " + ex.getMessage());
                return 1;
            })
            .setCaseInsensitiveEnumValuesAllowed(true)
            .setUnmatchedArgumentsAllowed(true)
            .setStopAtPositional(true);

        // Bypass must accept the child command's own flags (git log -n 5) verbatim,
        // so its parser stops treating tokens as options after the first positional.
        CommandLine bypass = cmd.getSubcommands().get("bypass");
        if (bypass != null) {
            bypass.setStopAtPositional(true);
            bypass.setUnmatchedArgumentsAllowed(true);
        }

        int exitCode;
        if (preParsed.isSubcommand()) {
            exitCode = cmd.execute(args);
        } else {
            rootCommand.setExplicitChildArgs(preParsed.childArgs());
            try {
                exitCode = cmd.execute(preParsed.condenseArgs().toArray(new String[0]));
            } finally {
                rootCommand.setExplicitChildArgs(null);
            }
        }

        // If the root command stored a passthrough exit code, use that instead
        Object result = cmd.getExecutionResult();
        if (result instanceof Integer passthroughExit) {
            exitCode = passthroughExit;
        }
        return com.condense.core.CommandExecutor.toOsExitCode(exitCode);
    }
}
