package com.condense.discover;

import com.condense.core.Mappers;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code condense discover} — recommend filter definitions from manifests and lockfiles.
 */
@Command(
    name = "discover",
    description = "Recommend filter definitions from repository manifests and lockfiles.",
    mixinStandardHelpOptions = true
)
@Dependent
@Unremovable
public class DiscoverCommand implements Callable<Integer> {

    @Option(
        names = "--format",
        description = "Output format: 'text' (default) or 'json'.",
        defaultValue = "text",
        paramLabel = "FORMAT"
    )
    String format;

    @Option(
        names = "--root",
        description = "Narrow the workspace root. May not widen past the detected repository root.",
        paramLabel = "DIR"
    )
    Path root;

    private final DiscoverService discover;

    public DiscoverCommand() {
        this(new DiscoverService());
    }

    public DiscoverCommand(DiscoverService discover) {
        this.discover = discover;
    }

    @Override
    public Integer call() {
        try {
            Path cwd = Path.of(System.getProperty("user.dir", "."));
            DiscoverReport report = discover.discover(cwd, root);
            if ("json".equalsIgnoreCase(format)) {
                System.out.println(Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            } else {
                printText(report);
            }
            return report.failed() ? 1 : 0;
        } catch (Exception e) {
            System.err.println("condense discover: error: " + e.getMessage());
            return 1;
        }
    }

    static void printText(DiscoverReport report) {
        if (report.failed()) {
            System.out.println("Discover failed: " + report.error());
            return;
        }
        System.out.println("Condense discover");
        System.out.println("Root:      " + report.root());
        System.out.println("Probed:    " + report.filesProbed()
            + "  read: " + report.filesRead()
            + "  bytes: " + report.bytesRead()
            + (report.truncated() ? "  truncated" : ""));
        if (report.families().isEmpty()) {
            System.out.println("Families:  (none)");
        } else {
            System.out.println("Families:");
            for (DiscoverReport.FamilyHit hit : report.families()) {
                System.out.println("  - " + hit.family() + " (" + hit.rule() + "): "
                    + String.join(", ", hit.recommend()));
            }
        }
        if (report.recommend().isEmpty()) {
            System.out.println("Recommend: (none)");
        } else {
            System.out.println("Recommend: " + String.join(", ", report.recommend()));
        }
        if (report.warnings() != null && !report.warnings().isEmpty()) {
            System.out.println("Warnings:");
            for (String warning : report.warnings()) {
                System.out.println("  - " + warning);
            }
        }
    }
}
