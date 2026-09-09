package com.condense.session;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects intra-session command corrections while strictly preserving session isolation.
 * Cross-session pairing is fundamentally prevented by design.
 */
public final class CorrectionCandidateDetector {

    private static final long MAX_CORRECTION_WINDOW_MILLIS = Duration.ofMinutes(30).toMillis();

    private CorrectionCandidateDetector() {}

    /**
     * Detects correction candidates within a single, isolated session.
     *
     * @param session the isolated session record
     * @return list of detected correction candidates
     */
    public static List<CorrectionCandidate> detectIntraSession(SessionRecord session) {
        if (session == null || session.events().size() < 2) {
            return List.of();
        }

        List<CorrectionCandidate> candidates = new ArrayList<>();
        List<SessionRecord.SessionEvent> events = session.events();

        SessionRecord.SessionEvent lastFailure = null;

        for (SessionRecord.SessionEvent event : events) {
            if (event.failed()) {
                lastFailure = event;
            } else if (event.succeeded() && lastFailure != null) {
                long delta = Duration.between(lastFailure.timestamp(), event.timestamp()).toMillis();
                if (delta >= 0 && delta <= MAX_CORRECTION_WINDOW_MILLIS) {
                    if (isRelatedCommand(lastFailure.command(), event.command())) {
                        candidates.add(new CorrectionCandidate(
                            session.sessionId(),
                            lastFailure.command(),
                            lastFailure.exitCode(),
                            lastFailure.outputSnippet(),
                            event.command(),
                            delta
                        ));
                        lastFailure = null; // Paired
                    }
                } else {
                    lastFailure = null; // Expired window
                }
            }
        }

        return candidates;
    }

    /**
     * Detects correction candidates across multiple sessions by analyzing each session independently.
     * Guarantees zero cross-session pairing.
     */
    public static List<CorrectionCandidate> detectAcrossSessions(List<SessionRecord> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }

        List<CorrectionCandidate> allCandidates = new ArrayList<>();
        for (SessionRecord session : sessions) {
            allCandidates.addAll(detectIntraSession(session));
        }
        return allCandidates;
    }

    private static boolean isRelatedCommand(String failedCmd, String succCmd) {
        String baseFailed = extractBase(failedCmd);
        String baseSucc = extractBase(succCmd);

        // Identical base command (e.g. pytest -> pytest -k ...)
        if (baseFailed.equalsIgnoreCase(baseSucc)) {
            return true;
        }

        // Common ecosystem workflows: e.g. npm install -> npm test, cargo build -> cargo test
        if ((baseFailed.contains("npm") && baseSucc.contains("npm"))
            || (baseFailed.contains("cargo") && baseSucc.contains("cargo"))
            || (baseFailed.contains("mvn") && baseSucc.contains("mvn"))
            || (baseFailed.contains("git") && baseSucc.contains("git"))
            || (baseFailed.contains("docker") && baseSucc.contains("docker"))) {
            return true;
        }

        return false;
    }

    private static String extractBase(String cmd) {
        String trimmed = cmd.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }
}
