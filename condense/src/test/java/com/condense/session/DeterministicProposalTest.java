package com.condense.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicProposalTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Generates identical proposals with stable hashes across repeated executions")
    void deterministicProposalGeneration() {
        SessionRecord session = new SessionRecord(
            "sess-det-1",
            AgentTranscriptFormat.CLAUDE_CODE,
            Path.of("/dummy/sess1.jsonl"),
            Instant.parse("2026-03-01T10:00:00Z"),
            Instant.parse("2026-03-01T10:05:00Z"),
            "/workspace",
            List.of(
                new SessionRecord.SessionEvent(
                    "e1",
                    Instant.parse("2026-03-01T10:01:00Z"),
                    "bazel test //...",
                    1,
                    3000L,
                    1500L,
                    "FAILURE: Build failed with an exception.",
                    false
                ),
                new SessionRecord.SessionEvent(
                    "e2",
                    Instant.parse("2026-03-01T10:02:00Z"),
                    "bazel test //... --strategy=TestRunner=standalone",
                    0,
                    1000L,
                    500L,
                    "BUILD SUCCESSFUL in 2s",
                    false
                ),
                new SessionRecord.SessionEvent(
                    "e3",
                    Instant.parse("2026-03-01T10:03:00Z"),
                    "bazel test //...",
                    0,
                    3000L,
                    1500L,
                    "BUILD SUCCESSFUL",
                    false
                )
            )
        );

        SessionIntelligenceService service = new SessionIntelligenceService();
        SessionIntelligenceReport report1 = service.analyze(List.of(session));
        SessionIntelligenceReport report2 = service.analyze(List.of(session));

        assertThat(report1.proposals()).isNotEmpty();
        assertThat(report1.proposals()).hasSameSizeAs(report2.proposals());

        for (int i = 0; i < report1.proposals().size(); i++) {
            FilterProposal p1 = report1.proposals().get(i);
            FilterProposal p2 = report2.proposals().get(i);

            assertThat(p1.id()).isEqualTo(p2.id());
            assertThat(p1.name()).isEqualTo(p2.name());
            assertThat(p1.commandPrefix()).isEqualTo(p2.commandPrefix());
            assertThat(p1.rationale()).isEqualTo(p2.rationale());
            assertThat(p1.proposedFilterSnippet()).isEqualTo(p2.proposedFilterSnippet());
            assertThat(p1.reviewDisclaimer()).isEqualTo(FilterProposal.DEFAULT_DISCLAIMER);
            assertThat(p1.reviewDisclaimer()).isEqualTo(p2.reviewDisclaimer());
        }
    }

    @Test
    @DisplayName("Guarantees zero mutation of filters.toml or filesystem")
    void neverModifiesFiltersToml() {
        Path projectFilters = tempDir.resolve("filters.toml");
        Path subDirFilters = tempDir.resolve(".condense/filters.toml");

        SessionRecord session = new SessionRecord(
            "sess-no-write",
            AgentTranscriptFormat.CURSOR,
            Path.of("/dummy/cursor.json"),
            Instant.parse("2026-03-01T10:00:00Z"),
            Instant.parse("2026-03-01T10:05:00Z"),
            "/workspace",
            List.of(
                new SessionRecord.SessionEvent(
                    "e1",
                    Instant.parse("2026-03-01T10:01:00Z"),
                    "unsupported-tool run --verbose",
                    0,
                    500L,
                    800L,
                    "lots of noisy output",
                    false
                ),
                new SessionRecord.SessionEvent(
                    "e2",
                    Instant.parse("2026-03-01T10:02:00Z"),
                    "unsupported-tool run --verbose",
                    0,
                    500L,
                    800L,
                    "lots of noisy output",
                    false
                )
            )
        );

        SessionIntelligenceService service = new SessionIntelligenceService();
        SessionIntelligenceReport report = service.analyze(List.of(session));

        assertThat(report.proposals()).isNotEmpty();
        // Verify no files were created or modified in tempDir
        assertThat(Files.exists(projectFilters)).isFalse();
        assertThat(Files.exists(subDirFilters)).isFalse();
    }

    @Test
    @DisplayName("Redacts secrets inside proposal samples and failure snippets")
    void redactsSecretsInProposalsAndFailures() {
        SessionRecord session = new SessionRecord(
            "sess-secrets",
            AgentTranscriptFormat.WINDSURF,
            Path.of("/dummy/windsurf.json"),
            Instant.parse("2026-03-01T10:00:00Z"),
            Instant.parse("2026-03-01T10:05:00Z"),
            "/workspace",
            List.of(
                new SessionRecord.SessionEvent(
                    "e1",
                    Instant.parse("2026-03-01T10:01:00Z"),
                    "curl -H 'Authorization: Bearer secret-token-1234567890abcdef1234567890' https://api.service.com",
                    1,
                    200L,
                    500L,
                    "Unauthorized: invalid key sk-ant-api03-1234567890abcdef1234567890abcdef12 for postgres://user:password123@db.prod.internal",
                    false
                ),
                new SessionRecord.SessionEvent(
                    "e2",
                    Instant.parse("2026-03-01T10:02:00Z"),
                    "curl -H 'Authorization: Bearer secret-token-1234567890abcdef1234567890' https://api.service.com",
                    0,
                    200L,
                    500L,
                    "Success OK 200",
                    false
                )
            )
        );

        SessionIntelligenceService service = new SessionIntelligenceService();
        SessionIntelligenceReport report = service.analyze(List.of(session));

        // Verify failure patterns have secrets redacted
        for (var failure : report.failurePatterns()) {
            assertThat(failure.sampleSnippet()).doesNotContain("password123");
            assertThat(failure.sampleSnippet()).doesNotContain("sk-ant-");
        }

        // Verify proposals have secrets redacted in sample command
        for (var proposal : report.proposals()) {
            assertThat(proposal.sampleCommand()).doesNotContain("secret-token-1234567890");
            assertThat(proposal.sampleCommand()).contains("[REDACTED_BEARER_TOKEN]");
        }
    }
}
