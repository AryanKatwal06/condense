package com.condense.session;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

/**
 * Aggregated session intelligence report containing failure metrics, unsupported command
 * discovery, and candidate proposals for human review.
 */
@RegisterForReflection
public record SessionIntelligenceReport(
    int totalSessions,
    int totalCommands,
    int failedCommands,
    int correctedPairsCount,
    long estimatedRawTokens,
    long estimatedSavedTokens,
    double estimatedSavingsRatio,
    List<UnsupportedCommandSummary> unsupportedCommands,
    List<FailurePatternSummary> failurePatterns,
    List<CorrectionCandidate> candidates,
    List<FilterProposal> proposals
) {
    @RegisterForReflection
    public record UnsupportedCommandSummary(
        String commandPrefix,
        int executionCount,
        long totalOutputChars,
        long estimatedTokens
    ) {}

    @RegisterForReflection
    public record FailurePatternSummary(
        String failureCategory,
        int count,
        String sampleSnippet
    ) {}
}
