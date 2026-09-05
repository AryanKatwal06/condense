package com.condense.propose;

/**
 * Hard caps and thresholds for adaptive proposals. Not configurable from TOML.
 */
public record ProposeLimits(
    int maxCommandRows,
    int maxOutcomeRows,
    int maxProposals,
    int minRuns,
    int minIncidents,
    int minRawTokens,
    long lookbackSeconds,
    int tailLines
) {
    public static final int DEFAULT_TAIL_LINES = 40;
    public static final long NINETY_DAYS_SECONDS = 90L * 24L * 60L * 60L;

    public static final ProposeLimits DEFAULT = new ProposeLimits(
        500,
        500,
        20,
        5,
        3,
        2000,
        NINETY_DAYS_SECONDS,
        DEFAULT_TAIL_LINES
    );
}
