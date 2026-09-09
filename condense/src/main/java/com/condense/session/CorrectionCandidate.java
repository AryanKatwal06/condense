package com.condense.session;

import java.util.Objects;

/**
 * A detected correction pattern where a failed command was subsequently followed
 * by a successful command within the same isolated session.
 */
public record CorrectionCandidate(
    String sessionId,
    String failedCommand,
    int failedExitCode,
    String failureSnippet,
    String correctedCommand,
    long timeDeltaMillis
) {
    public CorrectionCandidate {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(failedCommand, "failedCommand must not be null");
        Objects.requireNonNull(correctedCommand, "correctedCommand must not be null");
        failureSnippet = failureSnippet == null ? "" : failureSnippet;
    }

    /**
     * Returns the base executable of the failed command (e.g. "git" for "git status").
     */
    public String baseCommand() {
        String trimmed = failedCommand.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }
}
