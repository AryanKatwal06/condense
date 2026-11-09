package com.condense;

import com.condense.core.ProxyService;
import com.condense.core.TrackingRepository;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code condense bypass <cmd...>} — run one command completely unfiltered.
 *
 * <p>Stdout and stderr are forwarded verbatim and the child exit code is returned
 * unchanged. The argv is launched directly; no shell string is ever built.
 */
@Command(
    name = "bypass",
    description = {
        "Run a command without filtering.",
        "",
        "Raw stdout and stderr are forwarded unchanged and the child exit code is",
        "preserved. The command is executed directly with no shell interpolation."
    },
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "Examples:",
        "  condense bypass git log -n 5",
        "  condense bypass cargo test -- --nocapture"
    }
)
@Dependent
@Unremovable
public class BypassCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Inject
    ProxyService proxy;

    @Inject
    TrackingRepository tracking;

    @Parameters(index = "0..*", arity = "0..*", paramLabel = "COMMAND",
        description = "Command and arguments to run without filtering.")
    String[] command;

    @Override
    public Integer call() {
        if (command == null || command.length == 0) {
            spec.commandLine().usage(System.err);
            return 2;
        }

        ProxyService.Outcome outcome = null;
        try {
            outcome = proxy.bypass(List.of(command), System.out, System.err);
            return outcome.result().exitCode();
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("condense: error executing command: " + e.getMessage());
            return 1;
        } finally {
            if (outcome != null && outcome.result() != null) {
                outcome.result().cleanup();
            }
            if (tracking != null) {
                tracking.close();
            }
        }
    }
}
