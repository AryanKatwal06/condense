package com.condense;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import com.condense.core.ProxyService;
import com.condense.core.TrackingRepository;
import jakarta.inject.Inject;
import picocli.CommandLine.Parameters;
import java.util.Arrays;
import java.util.List;

@Command(
    name = "condense",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider.class,
    subcommands = {
        com.condense.analytics.GainCommand.class,
        com.condense.doctor.DoctorCommand.class,
        com.condense.discover.DiscoverCommand.class,
        com.condense.explain.ExplainCommand.class,
        com.condense.read.ReadCommand.class,
        com.condense.hooks.InitCommand.class,
        com.condense.config.ConfigCommand.class,
        com.condense.CompletionCommand.class,
        com.condense.update.UpdateCommand.class,
        com.condense.commands.McpCommand.class,
        com.condense.uninstall.UninstallCommand.class
    },
    description = {
        "High-performance CLI proxy that filters command output to save 60-90%% AI tokens.",
        "",
        "Condense sits between your AI coding assistant and the shell, filtering noisy",
        "command output so the AI receives a compact, dense summary instead of",
        "thousands of raw lines.",
        "",
        "Run 'condense gain' to see how many tokens you have saved."
    },
    synopsisHeading = "%nUsage: ",
    descriptionHeading = "%nDescription:%n",
    optionListHeading = "%nOptions:%n",
    commandListHeading = "%nCommands:%n",
    footer = {
        "",
        "Examples:",
        "  condense git status          # Filtered git status",
        "  condense cargo test          # Test failures only",
        "  condense pytest              # Failures + summary line",
        "  condense --format json pytest  # Schema-1 diagnostics document",
        "  condense gain                # Token savings report",
        "  condense doctor              # Why gain is empty",
        "  condense explain pytest      # Which stages dropped which lines",
        "  condense read Src.java       # Comment-stripped source with original line numbers",
        "  condense mcp --start         # MCP server on stdio",
        "  condense init -g             # Install AI tool hooks"
    }
)
@jakarta.enterprise.context.Dependent
@io.quarkus.picocli.runtime.annotations.TopCommand
public class CondenseRootCommand implements java.util.concurrent.Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Inject
    ProxyService proxy;

    @Inject
    TrackingRepository tracking;

    @Parameters(index = "0..*", hidden = true,
        description = "Command and arguments to proxy (e.g., git status)")
    String[] passthroughArgs;

    @Option(
        names = {"-v", "--verbose"},
        description = "Increase verbosity. Use -v, -vv, or -vvv."
    )
    boolean[] verbose = new boolean[0];

    @Option(
        names = {"-u", "--ultra-compact"},
        description = "Maximum compression: ASCII icons, inline format."
    )
    boolean ultraCompact;

    @Option(
        names = "--format",
        description = "Output format: 'text' (default) or 'json'. Options must come before the proxied command.",
        defaultValue = "text",
        paramLabel = "FORMAT"
    )
    String format;

    @picocli.CommandLine.Unmatched
    List<String> remainder;

    @Override
    public Integer call() {
        if ((passthroughArgs == null || passthroughArgs.length == 0) && (remainder == null || remainder.isEmpty())) {
            spec.commandLine().usage(System.out);
            return 0;
        }

        try {
            List<String> argList = new java.util.ArrayList<>();
            if (passthroughArgs != null) argList.addAll(Arrays.asList(passthroughArgs));
            if (remainder != null) argList.addAll(remainder);
            boolean json = "json".equalsIgnoreCase(format);
            ProxyService.Outcome outcome = proxy.run(
                argList, verbosityLevel(), ultraCompact, json, System.out, System.err);
            return outcome.result().exitCode();
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("condense: error executing command: " + e.getMessage());
            return 1;
        } finally {
            tracking.close();
        }
    }

    /** Returns verbosity level: 0 (silent), 1, 2, or 3 (most verbose). */
    public int verbosityLevel() {
        return verbose == null ? 0 : Math.min(verbose.length, 3);
    }
}
