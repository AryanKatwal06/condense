package com.condense.session;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Reviewable candidate filter proposal deterministically derived from session intelligence.
 * Condense presents these to the user for human review and never auto-modifies filters.toml.
 */
@RegisterForReflection
public record FilterProposal(
    String id,
    String name,
    String commandPrefix,
    String rationale,
    String sampleCommand,
    long estimatedTokenSavings,
    String proposedFilterSnippet,
    String reviewDisclaimer
) {
    public static final String DEFAULT_DISCLAIMER =
        "Candidate filter proposal for human review. To accept, add to filters.toml or run condense filter add. Condense never automatically modifies filters.toml.";

    public FilterProposal(
            String id,
            String name,
            String commandPrefix,
            String rationale,
            String sampleCommand,
            long estimatedTokenSavings,
            String proposedFilterSnippet) {
        this(id, name, commandPrefix, rationale, sampleCommand, estimatedTokenSavings, proposedFilterSnippet, DEFAULT_DISCLAIMER);
    }
}
