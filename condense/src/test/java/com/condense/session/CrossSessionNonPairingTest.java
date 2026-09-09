package com.condense.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrossSessionNonPairingTest {

    @Test
    @DisplayName("Guarantees that failures in one session are never paired with successes in another session")
    void crossSessionNonPairingGuaranteed() {
        Instant now = Instant.now();

        // Session 1: failed build, never resolved in this session
        SessionRecord session1 = new SessionRecord(
            "session-01",
            AgentTranscriptFormat.CLAUDE_CODE,
            Path.of("/tmp/s1.jsonl"),
            now.minus(2, ChronoUnit.HOURS),
            now.minus(1, ChronoUnit.HOURS),
            "/workspace/proj",
            List.of(
                new SessionRecord.SessionEvent(
                    "evt-1-1",
                    now.minus(100, ChronoUnit.MINUTES),
                    "git status",
                    0,
                    100L,
                    50L,
                    "clean",
                    false
                ),
                new SessionRecord.SessionEvent(
                    "evt-1-2",
                    now.minus(70, ChronoUnit.MINUTES),
                    "cargo test --test integration",
                    101, // Failed exit code
                    5000L,
                    1200L,
                    "error: test failed",
                    false
                )
            )
        );

        // Session 2: started later, has a successful cargo test as first command
        SessionRecord session2 = new SessionRecord(
            "session-02",
            AgentTranscriptFormat.CLAUDE_CODE,
            Path.of("/tmp/s2.jsonl"),
            now.minus(30, ChronoUnit.MINUTES),
            now,
            "/workspace/proj",
            List.of(
                new SessionRecord.SessionEvent(
                    "evt-2-1",
                    now.minus(20, ChronoUnit.MINUTES),
                    "cargo test --test integration",
                    0, // Success
                    4500L,
                    400L,
                    "test result: ok",
                    false
                ),
                new SessionRecord.SessionEvent(
                    "evt-2-2",
                    now.minus(10, ChronoUnit.MINUTES),
                    "cargo test --test unit",
                    1, // Failure in session 2
                    2000L,
                    500L,
                    "test result: FAILED",
                    false
                ),
                new SessionRecord.SessionEvent(
                    "evt-2-3",
                    now.minus(5, ChronoUnit.MINUTES),
                    "cargo test --test unit -- --nocapture",
                    0, // Success in session 2 resolving evt-2-2
                    2100L,
                    500L,
                    "test result: ok",
                    false
                )
            )
        );

        // Run multi-session detection
        List<CorrectionCandidate> candidates = CorrectionCandidateDetector.detectAcrossSessions(List.of(session1, session2));

        // Exactly ONE candidate should be found: the intra-session pair inside session 2 (evt-2-2 -> evt-2-3)
        assertThat(candidates).hasSize(1);
        CorrectionCandidate candidate = candidates.get(0);
        assertThat(candidate.sessionId()).isEqualTo("session-02");
        assertThat(candidate.failedCommand()).isEqualTo("cargo test --test unit");
        assertThat(candidate.correctedCommand()).isEqualTo("cargo test --test unit -- --nocapture");

        // Verify session 1's unresolved failure was NOT paired across session boundary with session 2's evt-2-1
        assertThat(candidates).noneMatch(c -> c.sessionId().equals("session-01"));
        assertThat(candidates).noneMatch(c -> c.failedCommand().equals("cargo test --test integration"));
    }

    @Test
    @DisplayName("Maintains chronological ordering within session")
    void chronologicalIntraSessionPairing() {
        Instant now = Instant.now();

        SessionRecord session = new SessionRecord(
            "session-chronology",
            AgentTranscriptFormat.CURSOR,
            Path.of("/tmp/cursor.json"),
            now.minus(10, ChronoUnit.MINUTES),
            now,
            "/workspace/app",
            List.of(
                new SessionRecord.SessionEvent("e1", now.minus(8, ChronoUnit.MINUTES), "npm run build", 1, 1000L, 500L, "TS error", false),
                new SessionRecord.SessionEvent("e2", now.minus(5, ChronoUnit.MINUTES), "npm run build", 0, 1200L, 200L, "Compiled successfully", false)
            )
        );

        List<CorrectionCandidate> candidates = CorrectionCandidateDetector.detectIntraSession(session);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).timeDeltaMillis()).isEqualTo(3 * 60 * 1000L);
    }
}
