package com.zapproxy;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import com.zapproxy.core.*;
import jakarta.inject.Inject;
import picocli.CommandLine.Parameters;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Command(
    name = "zap",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider.class,
    subcommands = {
        com.zapproxy.analytics.GainCommand.class,
        com.zapproxy.hooks.InitCommand.class,
        com.zapproxy.config.ConfigCommand.class
    },
    description = {
        "High-performance CLI proxy that filters command output to save 60-90%% AI tokens.",
        "",
        "Zap sits between your AI coding assistant and the shell, filtering noisy",
        "command output so the AI receives a compact, dense summary instead of",
        "thousands of raw lines.",
        "",
        "Run 'zap gain' to see how many tokens you have saved."
    },
    synopsisHeading = "%nUsage: ",
    descriptionHeading = "%nDescription:%n",
    optionListHeading = "%nOptions:%n",
    commandListHeading = "%nCommands:%n",
    footer = {
        "",
        "Examples:",
        "  zap git status          # Filtered git status",
        "  zap cargo test          # Test failures only",
        "  zap pytest              # Failures + summary line",
        "  zap gain                # Token savings report",
        "  zap init -g             # Install AI tool hooks"
    }
)
@jakarta.enterprise.context.Dependent
@io.quarkus.picocli.runtime.annotations.TopCommand
public class ZapRootCommand implements java.util.concurrent.Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Inject
    CommandExecutor executor;

    @Inject
    StrategyRegistry registry;

    @Inject
    TrackingRepository tracking;

    @Inject
    TeeWriter teeWriter;

    @Inject
    ConfigLoader configLoader;

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

    @picocli.CommandLine.Unmatched
    List<String> remainder;

    @Override
    public Integer call() {
        if ((passthroughArgs == null || passthroughArgs.length == 0) && (remainder == null || remainder.isEmpty())) {
            // No args: print help
            spec.commandLine().usage(System.out);
            return 0;
        }

        // Dispatch through filter pipeline
        try {
            List<String> argList = new java.util.ArrayList<>();
            if (passthroughArgs != null) argList.addAll(Arrays.asList(passthroughArgs));
            if (remainder != null) argList.addAll(remainder);
            String commandStr = String.join(" ", argList);

            // Execute the real command
            ExecutionResult result = executor.execute(argList);

            // Find and apply the appropriate filter
            FilterStrategy strategy = registry.lookup(argList.toArray(new String[0]));
            ZapConfig config = configLoader.load();
            FilterResult filtered = strategy.apply(
                commandStr, result, config, verbosityLevel(), ultraCompact);

            // Maybe save raw output to tee file
            Path teePath = teeWriter.maybeDump(commandStr, result);

            // Print filtered output
            System.out.print(filtered.output());
            if (!filtered.output().endsWith("\n")) System.out.println();

            // Append tee path if saved
            if (teePath != null) {
                System.out.println("[raw output saved to: " + teePath + "]");
            }

            // Flush output so caller (AI) gets it immediately
            System.out.flush();

            // Synchronous analytics insertion AFTER output is flushed
            tracking.insert(
                commandStr,
                ProjectFingerprint.ofCurrentDir(),
                System.getProperty("user.dir"),
                filtered.rawTokens(),
                filtered.outTokens(),
                result.durationMs()
            );

            // Return the original exit code
            return result.exitCode();

        } catch (IllegalStateException e) {
            // Infinite loop guard triggered
            System.err.println(e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("zap: error executing command: " + e.getMessage());
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
