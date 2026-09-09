package com.condense.session;

import com.condense.core.Mappers;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code condense session} — Privacy-preserving session intelligence and real-world failure visibility.
 */
@Command(
    name = "session",
    description = "Privacy-preserving session intelligence and real-world failure visibility.",
    mixinStandardHelpOptions = true,
    subcommands = {
        SessionCommand.AnalyzeCommand.class
    }
)
@Dependent
@Unremovable
public class SessionCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        // Default to running analyze if no subcommand is specified
        return new AnalyzeCommand().call();
    }

    @Command(
        name = "analyze",
        description = "Analyze local agent transcripts for token savings, failure patterns, and candidate proposals.",
        mixinStandardHelpOptions = true
    )
    @Dependent
    @Unremovable
    public static class AnalyzeCommand implements Callable<Integer> {

        @Option(
            names = "--agent",
            description = "Target agent format: 'claude', 'cursor', 'windsurf', or 'all' (default: all).",
            defaultValue = "all"
        )
        String agent = "all";

        @Option(
            names = "--path",
            description = "Custom transcript directory or file path."
        )
        Path path;

        @Option(
            names = "--max-age-days",
            description = "Maximum age of sessions in days (default: 7).",
            defaultValue = "7"
        )
        int maxAgeDays = 7;

        @Option(
            names = "--format",
            description = "Output format: 'text' (default), 'json', or 'summary'.",
            defaultValue = "text"
        )
        String format = "text";

        @Option(
            names = "--output",
            description = "Optional file path to export analysis report JSON."
        )
        Path output;

        private final SessionIntelligenceService service;
        private final PrintStream out;

        public AnalyzeCommand() {
            this(new SessionIntelligenceService(), System.out);
        }

        public AnalyzeCommand(SessionIntelligenceService service, PrintStream out) {
            this.service = service;
            this.out = out != null ? out : System.out;
        }

        @Override
        public Integer call() {
            try {
                AgentTranscriptFormat transcriptFormat = null;
                if (!"all".equalsIgnoreCase(agent)) {
                    var opt = AgentTranscriptFormat.fromId(agent);
                    if (opt.isEmpty()) {
                        out.println("Unknown agent format: " + agent + ". Supported: claude, cursor, windsurf, all.");
                        return 1;
                    }
                    transcriptFormat = opt.get();
                }

                SessionIntelligenceReport report = service.analyzePath(path, transcriptFormat, maxAgeDays);

                if ("json".equalsIgnoreCase(format)) {
                    String json = Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
                    out.println(json);
                } else if ("summary".equalsIgnoreCase(format)) {
                    printSummary(report);
                } else {
                    printText(report);
                }

                if (output != null) {
                    String json = Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
                    Files.writeString(output, json);
                    if (!"json".equalsIgnoreCase(format)) {
                        out.println("Exported report to: " + output.toAbsolutePath());
                    }
                }

                return 0;
            } catch (Exception e) {
                out.println("condense session analyze: error: " + e.getMessage());
                return 1;
            }
        }

        private void printSummary(SessionIntelligenceReport report) {
            out.printf(
                "Analyzed %d sessions (%d commands, %d failures). Saved ~%d tokens (%.1f%%). Proposals: %d.%n",
                report.totalSessions(),
                report.totalCommands(),
                report.failedCommands(),
                report.estimatedSavedTokens(),
                report.estimatedSavingsRatio() * 100.0,
                report.proposals().size()
            );
        }

        private void printText(SessionIntelligenceReport report) {
            out.println("=== Condense Session Intelligence ===");
            out.printf("Sessions analyzed:         %d%n", report.totalSessions());
            out.printf("Commands evaluated:        %d%n", report.totalCommands());
            out.printf("Failed commands:           %d%n", report.failedCommands());
            out.printf("Correction pairs detected: %d%n", report.correctedPairsCount());
            out.printf("Estimated raw tokens:      %,d%n", report.estimatedRawTokens());
            out.printf("Estimated saved tokens:    %,d (%.1f%%)%n",
                report.estimatedSavedTokens(), report.estimatedSavingsRatio() * 100.0);
            out.println();

            if (!report.unsupportedCommands().isEmpty()) {
                out.println("--- Unsupported High-Volume Commands ---");
                for (var cmd : report.unsupportedCommands()) {
                    out.printf("  %-25s (%d runs, ~%,d tokens)%n",
                        cmd.commandPrefix(), cmd.executionCount(), cmd.estimatedTokens());
                }
                out.println();
            }

            if (!report.failurePatterns().isEmpty()) {
                out.println("--- Real-World Failure Patterns ---");
                for (var pattern : report.failurePatterns()) {
                    out.printf("  %-20s (%d): %s%n",
                        pattern.failureCategory(), pattern.count(), pattern.sampleSnippet());
                }
                out.println();
            }

            if (!report.proposals().isEmpty()) {
                out.println("--- Candidate Filter Proposals (Review Only) ---");
                for (FilterProposal p : report.proposals()) {
                    out.printf("[ID: %s] %s%n", p.id(), p.name());
                    out.printf("  Rationale: %s%n", p.rationale());
                    if (p.sampleCommand() != null) {
                        out.printf("  Sample:    %s%n", p.sampleCommand());
                    }
                    out.printf("  Estimated savings: ~%,d tokens%n", p.estimatedTokenSavings());
                    out.println("  Suggested definition:");
                    for (String line : p.proposedFilterSnippet().lines().toList()) {
                        out.println("    " + line);
                    }
                    out.println();
                }
                out.println("Note: Candidate filter proposals are for human review only. Condense never automatically modifies filters.toml.");
            } else {
                out.println("No candidate proposals generated.");
            }
        }
    }
}
