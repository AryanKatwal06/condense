package com.condense.session;

import com.condense.filter.pipeline.config.BuiltinDefinition;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Privacy-preserving session intelligence service.
 * Scans local agent transcripts, computes token savings and failure classifications,
 * detects correction candidates, and derives reviewable proposals.
 * Never writes to or mutates filters.toml or user configuration.
 */
public final class SessionIntelligenceService {

    private static final int MAX_DISCOVERED_FILES = 100;
    private static final long MAX_SESSION_BYTES = 2_000_000L; // 2MB per session

    private final BuiltinDefinitionCatalog catalog;

    public SessionIntelligenceService() {
        this(BuiltinDefinitionCatalog.standalone());
    }

    public SessionIntelligenceService(BuiltinDefinitionCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Analyzes sessions parsed from the specified path and format.
     */
    public SessionIntelligenceReport analyzePath(Path rootPath, AgentTranscriptFormat format, int maxAgeDays) {
        List<SessionRecord> sessions = new ArrayList<>();
        List<SessionReader> readers = format != null
                ? SessionReaderRegistry.find(format).map(List::of).orElse(List.of())
                : SessionReaderRegistry.all();

        Path userHome = Path.of(System.getProperty("user.home", "."));

        for (SessionReader reader : readers) {
            Path targetDir = rootPath != null ? rootPath : reader.format().defaultBaseDir(userHome);
            if (targetDir == null || !Files.exists(targetDir)) {
                continue;
            }

            try {
                List<Path> files = reader.discoverSessionFiles(targetDir, maxAgeDays, MAX_DISCOVERED_FILES);
                for (Path file : files) {
                    try {
                        SessionRecord record = reader.parseSession(file, MAX_SESSION_BYTES);
                        if (record != null && !record.events().isEmpty()) {
                            sessions.add(record);
                        }
                    } catch (IOException e) {
                        // Fail-open for corrupted individual session files
                    }
                }
            } catch (IOException e) {
                // Fail-open for unreadable transcript directories
            }
        }

        return analyze(sessions);
    }

    /**
     * Evaluates in-memory session records. Guarantees 100% read-only operation.
     */
    public SessionIntelligenceReport analyze(List<SessionRecord> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return new SessionIntelligenceReport(
                0, 0, 0, 0, 0L, 0L, 0.0,
                List.of(), List.of(), List.of(), List.of()
            );
        }

        int totalSessions = sessions.size();
        int totalCommands = 0;
        int failedCommands = 0;
        long estimatedRawTokens = 0L;
        long estimatedSavedTokens = 0L;

        List<CorrectionCandidate> allCandidates = new ArrayList<>();
        Map<String, CommandUsageAccumulator> unsupportedCommandMap = new LinkedHashMap<>();
        Map<String, FailureCategoryAccumulator> failureCategoryMap = new LinkedHashMap<>();

        for (SessionRecord session : sessions) {
            // Strictly detect corrections within this session boundary only
            List<CorrectionCandidate> sessionCandidates = CorrectionCandidateDetector.detectIntraSession(session);
            allCandidates.addAll(sessionCandidates);

            for (SessionRecord.SessionEvent event : session.events()) {
                String rawCommand = event.command();
                if (rawCommand == null || rawCommand.isBlank()) {
                    continue;
                }
                totalCommands++;

                // Redact commands and output snippets for privacy
                String redactedCommand = SecretRedactor.redact(rawCommand.trim());
                String redactedOutput = SecretRedactor.redact(event.outputSnippet());

                long outputChars = (redactedOutput != null ? redactedOutput.length() : 0);
                long tokens = Math.max(1, (event.rawOutputBytes() > 0 ? event.rawOutputBytes() : outputChars) / 4);
                estimatedRawTokens += tokens;

                if (event.failed()) {
                    failedCommands++;
                    classifyFailure(redactedCommand, redactedOutput, failureCategoryMap);
                }

                BuiltinDefinition match = catalog != null ? catalog.findByCommand(redactedCommand) : null;
                if (match != null || event.filtered()) {
                    // Supported or filtered command: estimate savings (~75% reduction on noisy commands)
                    long saved = Math.round(tokens * 0.75);
                    estimatedSavedTokens += saved;
                } else {
                    // Unsupported command
                    String prefix = extractCommandPrefix(redactedCommand);
                    unsupportedCommandMap.computeIfAbsent(prefix, k -> new CommandUsageAccumulator(prefix))
                            .record(outputChars, tokens, redactedCommand);
                }
            }
        }

        double savingsRatio = estimatedRawTokens > 0
                ? (double) estimatedSavedTokens / (double) estimatedRawTokens
                : 0.0;

        // Build summaries
        List<SessionIntelligenceReport.UnsupportedCommandSummary> unsupportedSummaries = new ArrayList<>();
        for (CommandUsageAccumulator acc : unsupportedCommandMap.values()) {
            unsupportedSummaries.add(new SessionIntelligenceReport.UnsupportedCommandSummary(
                acc.prefix,
                acc.executionCount,
                acc.totalOutputChars,
                acc.estimatedTokens
            ));
        }
        unsupportedSummaries.sort(Comparator.comparingLong(
            SessionIntelligenceReport.UnsupportedCommandSummary::estimatedTokens).reversed());

        List<SessionIntelligenceReport.FailurePatternSummary> failureSummaries = new ArrayList<>();
        for (FailureCategoryAccumulator acc : failureCategoryMap.values()) {
            failureSummaries.add(new SessionIntelligenceReport.FailurePatternSummary(
                acc.category,
                acc.count,
                acc.sampleSnippet
            ));
        }
        failureSummaries.sort(Comparator.comparingInt(SessionIntelligenceReport.FailurePatternSummary::count).reversed());

        // Derive deterministic reviewable proposals
        List<FilterProposal> proposals = deriveProposals(unsupportedCommandMap, allCandidates);

        return new SessionIntelligenceReport(
            totalSessions,
            totalCommands,
            failedCommands,
            allCandidates.size(),
            estimatedRawTokens,
            estimatedSavedTokens,
            savingsRatio,
            Collections.unmodifiableList(unsupportedSummaries),
            Collections.unmodifiableList(failureSummaries),
            Collections.unmodifiableList(allCandidates),
            Collections.unmodifiableList(proposals)
        );
    }

    private void classifyFailure(
            String command,
            String output,
            Map<String, FailureCategoryAccumulator> map) {
        String combined = (output != null ? output : "").toLowerCase(Locale.ROOT);
        String category = "GENERAL_FAILURE";

        if (combined.contains("cannot find symbol")
                || combined.contains("compilation failed")
                || combined.contains("syntax error")
                || combined.contains("build failed")
                || combined.contains("javac")
                || combined.matches(".*\\bts\\d{4}\\b.*")) {
            category = "COMPILATION_ERROR";
        } else if (combined.contains("assertionerror")
                || combined.contains("failures:")
                || combined.contains("test failed")
                || combined.contains("tests failed")
                || combined.contains("junit")
                || combined.contains("pytest")) {
            category = "TEST_FAILURE";
        } else if (combined.contains("eslint")
                || combined.contains("prettier")
                || combined.contains("spotless")
                || combined.contains("checkstyle")
                || combined.contains("rubocop")
                || combined.contains("linter error")) {
            category = "LINT_ERROR";
        } else if (combined.contains("conflict")
                || combined.contains("merge conflict")
                || combined.contains("fatal: not a git")
                || combined.contains("rejected")) {
            category = "GIT_CONFLICT";
        } else if (combined.contains("permission denied")
                || combined.contains("eacces")
                || combined.contains("operation not permitted")
                || combined.contains("access is denied")) {
            category = "PERMISSION_DENIED";
        }

        String sample = output;
        if (sample == null || sample.isBlank()) {
            sample = "Command exited with non-zero status";
        } else {
            sample = sample.strip().lines().findFirst().orElse(sample).trim();
            if (sample.length() > 100) {
                sample = sample.substring(0, 97) + "...";
            }
        }

        final String finalSample = sample;
        map.computeIfAbsent(category, k -> new FailureCategoryAccumulator(k))
                .record(finalSample);
    }

    private String extractCommandPrefix(String command) {
        if (command == null || command.isBlank()) {
            return "unknown";
        }
        String[] tokens = command.trim().split("\\s+");
        if (tokens.length == 0) {
            return "unknown";
        }
        if (tokens.length == 1) {
            return tokens[0];
        }
        String first = tokens[0].toLowerCase(Locale.ROOT);
        if (first.equals("bundle") || first.equals("npm") || first.equals("pnpm")
                || first.equals("yarn") || first.equals("mvn") || first.equals("gradle")
                || first.equals("git") || first.equals("cargo") || first.equals("docker")) {
            return tokens[0] + " " + tokens[1];
        }
        return tokens[0];
    }

    private List<FilterProposal> deriveProposals(
            Map<String, CommandUsageAccumulator> unsupported,
            List<CorrectionCandidate> candidates) {
        List<FilterProposal> proposals = new ArrayList<>();

        for (CommandUsageAccumulator acc : unsupported.values()) {
            // Check if this command prefix appears in any correction candidate
            long correctionsForCommand = candidates.stream()
                .filter(c -> c.failedCommand().startsWith(acc.prefix) || c.correctedCommand().startsWith(acc.prefix))
                .count();

            if (acc.executionCount >= 2 || correctionsForCommand > 0) {
                String slug = acc.prefix.replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase(Locale.ROOT);
                if (slug.startsWith("-")) slug = slug.substring(1);
                if (slug.endsWith("-")) slug = slug.substring(0, slug.length() - 1);

                String id = generateProposalId(slug, acc.prefix);
                String rationale = String.format(
                    "Observed %d executions producing ~%d tokens (%d failures corrected by user).",
                    acc.executionCount, acc.estimatedTokens, correctionsForCommand
                );

                long estimatedSavings = Math.round(acc.estimatedTokens * 0.70);
                String snippet = String.format(
                    "[filters.%s]%ncommand = \"%s\"%ndescription = \"Community filter proposal for %s\"%npipeline = [\"strip-ansi\", \"head-tail\"]",
                    slug, acc.prefix, acc.prefix
                );

                proposals.add(new FilterProposal(
                    id,
                    slug,
                    acc.prefix,
                    rationale,
                    acc.sampleCommand,
                    estimatedSavings,
                    snippet
                ));
            }
        }

        proposals.sort(Comparator.comparing(FilterProposal::id));
        return proposals;
    }

    private String generateProposalId(String slug, String prefix) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((slug + ":" + prefix).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString((slug + ":" + prefix).hashCode());
        }
    }

    private static final class CommandUsageAccumulator {
        final String prefix;
        int executionCount;
        long totalOutputChars;
        long estimatedTokens;
        String sampleCommand;

        CommandUsageAccumulator(String prefix) {
            this.prefix = prefix;
        }

        void record(long chars, long tokens, String sample) {
            this.executionCount++;
            this.totalOutputChars += chars;
            this.estimatedTokens += tokens;
            if (this.sampleCommand == null) {
                this.sampleCommand = sample;
            }
        }
    }

    private static final class FailureCategoryAccumulator {
        final String category;
        int count;
        String sampleSnippet;

        FailureCategoryAccumulator(String category) {
            this.category = category;
        }

        void record(String sample) {
            this.count++;
            if (this.sampleSnippet == null) {
                this.sampleSnippet = sample;
            }
        }
    }
}
