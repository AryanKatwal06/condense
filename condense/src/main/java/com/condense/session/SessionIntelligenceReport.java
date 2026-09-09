package com.condense.session;

import java.util.List;

/**
 * Aggregated session intelligence report containing failure metrics, unsupported command
 * discovery, and candidate proposals for human review.
 */
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
    public record UnsupportedCommandSummary(
        String commandPrefix,
        int executionCount,
        long totalOutputChars,
        long estimatedTokens
    ) {}

    public record FailurePatternSummary(
        String failureCategory,
        int count,
        String sampleSnippet
    ) {}
}
