package com.condense.propose;

import com.condense.core.Mappers;
import com.condense.core.TrackingRepository;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code condense propose} — reviewable project override diffs. Never writes {@code filters.toml}.
 */
@Command(
    name = "propose",
    description = "Propose reviewable project filter overrides from discovery and analytics.",
    mixinStandardHelpOptions = true
)
@Dependent
@Unremovable
public class ProposeCommand implements Callable<Integer> {

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

    @Option(
        names = "--write",
        description = "Write .condense/filters.toml.proposed. Never writes filters.toml."
    )
    boolean write;

    private final ProposeService propose;
    private final ProposeWriter writer;
    private final TrackingRepository tracking;

    public ProposeCommand() {
        this(null);
    }

    @Inject
    public ProposeCommand(TrackingRepository tracking) {
        ProposeService service = new ProposeService();
        this.propose = service;
        this.writer = new ProposeWriter(service);
        this.tracking = tracking;
    }

    public ProposeCommand(ProposeService propose, ProposeWriter writer, TrackingRepository tracking) {
        this.propose = propose;
        this.writer = writer;
        this.tracking = tracking;
    }

    @Override
    public Integer call() {
        try {
            Path cwd = Path.of(System.getProperty("user.dir", "."));
            ProposeReport report = propose.propose(cwd, root, tracking);
            if ("json".equalsIgnoreCase(format)) {
                System.out.println(Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            } else {
                printText(report);
            }
            if (report.failed()) {
                return 1;
            }
            if (write) {
                Path written = writer.write(Path.of(report.root()), report);
                if (!"json".equalsIgnoreCase(format)) {
                    System.out.println("Wrote:     " + written);
                }
            }
            return 0;
        } catch (Exception e) {
            System.err.println("condense propose: error: " + e.getMessage());
            return 1;
        }
    }

    static void printText(ProposeReport report) {
        if (report.failed()) {
            System.out.println("Propose failed: " + report.error());
            return;
        }
        System.out.println("Condense propose");
        System.out.println("Root:      " + report.root());
        if (report.discoverRecommend().isEmpty()) {
            System.out.println("Discover:  (none)");
        } else {
            System.out.println("Discover:  " + String.join(", ", report.discoverRecommend()));
        }
        if (report.analyticsUnavailable()) {
            System.out.println("Analytics: unavailable");
        }
        if (report.truncated()) {
            System.out.println("Truncated: true");
        }
        if (report.proposals().isEmpty()) {
            System.out.println("Proposals: (none)");
        } else {
            System.out.println("Proposals:");
            for (ProposeReport.Proposal proposal : report.proposals()) {
                System.out.println("  - " + proposal.kind()
                    + " [" + proposal.status() + "] "
                    + proposal.command()
                    + " (" + proposal.requiredCapability() + ")");
                if (proposal.evidence() != null && proposal.evidence().reason() != null) {
                    System.out.println("      " + proposal.evidence().reason());
                }
            }
        }
        if (report.warnings() != null && !report.warnings().isEmpty()) {
            System.out.println("Warnings:");
            for (String warning : report.warnings()) {
                System.out.println("  - " + warning);
            }
        }
        System.out.println("Does not write .condense/filters.toml. Review, copy, then condense config trust.");
    }
}
