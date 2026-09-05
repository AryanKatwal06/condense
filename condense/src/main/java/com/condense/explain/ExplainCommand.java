package com.condense.explain;

import com.condense.core.Mappers;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Unmatched;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code condense explain} — per-stage line and token accounting for a command.
 *
 * <p>Options must come before the proxied command because the root parser
 * stops at the first positional ({@code explain --format json pytest}).
 */
@Command(
    name = "explain",
    description = "Show which filter stages dropped or rewrote command output.",
    mixinStandardHelpOptions = true
)
@Dependent
@Unremovable
public class ExplainCommand implements Callable<Integer> {

    @Option(
        names = "--format",
        description = "Output format: 'text' (default) or 'json'.",
        defaultValue = "text",
        paramLabel = "FORMAT"
    )
    String format;

    @Option(
        names = "--input",
        description = "Use FILE as captured stdout instead of executing the command.",
        paramLabel = "FILE"
    )
    Path inputFile;

    @Option(
        names = "--stdin",
        description = "Read captured stdout from standard input instead of executing."
    )
    boolean stdin;

    @Option(
        names = "--exit-code",
        description = "Exit code to attach when using --input or --stdin. Default: 0.",
        defaultValue = "0",
        paramLabel = "N"
    )
    int exitCode;

    @Option(
        names = "--dropped-limit",
        description = "Maximum dropped/added lines printed per stage. 0 keeps counts only. Default: 32.",
        defaultValue = "32",
        paramLabel = "N"
    )
    int droppedLimit;

    @Option(
        names = {"-v", "--verbose"},
        description = "Verbosity passed to the filter, as in proxy mode."
    )
    boolean[] verbose = new boolean[0];

    @Option(
        names = {"-u", "--ultra-compact"},
        description = "Ask the filter for ultra-compact output."
    )
    boolean ultraCompact;

    @Parameters(
        index = "0..*",
        arity = "0..*",
        description = "Command to explain (e.g. git status). Options must come first."
    )
    List<String> commandArgs;

    @Unmatched
    List<String> remainder;

    @Inject
    ExplainService explain;

    public ExplainCommand() {}

    public ExplainCommand(ExplainService explain) {
        this.explain = explain;
    }

    @Override
    public Integer call() {
        try {
            if (inputFile != null && stdin) {
                System.err.println("condense explain: use either --input or --stdin, not both");
                return 1;
            }
            List<String> args = mergedArgs();
            if (args.isEmpty()) {
                System.err.println("condense explain: missing command. Example: condense explain --format json pytest");
                return 1;
            }
            int verbosity = verbose == null ? 0 : Math.min(verbose.length, 3);
            int limit = Math.max(0, droppedLimit);
            ExplainReport report;
            if (inputFile != null) {
                report = explain.explainInput(args, inputFile, exitCode, verbosity, ultraCompact, limit, cwd());
            } else if (stdin) {
                report = explain.explainStdin(
                    args, System.in.readAllBytes(), exitCode, verbosity, ultraCompact, limit, cwd());
            } else {
                report = explain.explainExecuted(args, verbosity, ultraCompact, limit);
            }
            if ("json".equalsIgnoreCase(format)) {
                System.out.println(Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            } else {
                System.out.print(ExplainTextRenderer.render(report));
            }
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("condense explain: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("condense explain: error: " + e.getMessage());
            return 1;
        }
    }

    private List<String> mergedArgs() {
        List<String> args = new ArrayList<>();
        if (commandArgs != null) {
            args.addAll(commandArgs);
        }
        if (remainder != null) {
            args.addAll(remainder);
        }
        return args;
    }

    private static Path cwd() {
        return Path.of(System.getProperty("user.dir", "."));
    }
}
