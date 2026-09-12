package com.condense.session;

import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompoundCommandSessionTest {

    private final BuiltinDefinitionCatalog catalog = BuiltinDefinitionCatalog.standalone();
    private final SessionIntelligenceService service = new SessionIntelligenceService(catalog);

    @Test
    @DisplayName("Compound command cd && npm test is decomposed and correctly recognized as supported")
    void compoundCommand_cdAndNpmTest_recognizedAsSupported() {
        SessionRecord.SessionEvent event = new SessionRecord.SessionEvent(
            "cmd-1",
            Instant.now(),
            "cd /workspace/web && npm test",
            0,
            100L,
            500L,
            "PASS src/app.test.tsx\n5 passed\n",
            false
        );

        SessionRecord session = new SessionRecord(
            "session-1",
            AgentTranscriptFormat.CURSOR,
            Path.of("test.json"),
            Instant.now(),
            Instant.now(),
            "/workspace",
            List.of(event)
        );

        SessionIntelligenceReport report = service.analyze(List.of(session));

        // Verification 1: Command was processed
        assertThat(report.totalCommands()).isEqualTo(1);

        // Verification 2: Because npm test was recognized in the compound chain, savings are credited
        assertThat(report.estimatedSavedTokens()).isPositive();
        assertThat(report.estimatedSavingsRatio()).isGreaterThan(0.5);

        // Verification 3: cd is not registered as an unsupported command
        assertThat(report.unsupportedCommands())
            .noneMatch(u -> u.commandPrefix().equals("cd"));
    }

    @Test
    @DisplayName("Compound command with multiple unsupported tools tracks each segment distinctly")
    void compoundCommand_unsupportedTools_tracksEachSegment() {
        SessionRecord.SessionEvent event = new SessionRecord.SessionEvent(
            "cmd-2",
            Instant.now(),
            "custom_lint_tool --strict && custom_sync_tool --remote prod",
            0,
            120L,
            400L,
            "lint clean\nsync finished\n",
            false
        );

        SessionRecord session = new SessionRecord(
            "session-2",
            AgentTranscriptFormat.CLAUDE_CODE,
            Path.of("test.jsonl"),
            Instant.now(),
            Instant.now(),
            "/workspace",
            List.of(event)
        );

        SessionIntelligenceReport report = service.analyze(List.of(session));

        assertThat(report.totalCommands()).isEqualTo(1);
        assertThat(report.estimatedSavedTokens()).isZero();

        // Both unsupported commands are tracked
        assertThat(report.unsupportedCommands())
            .extracting(SessionIntelligenceReport.UnsupportedCommandSummary::commandPrefix)
            .contains("custom_lint_tool", "custom_sync_tool");
    }

    @Test
    @DisplayName("Wrapped command with sudo and environment variables is correctly analyzed")
    void wrappedCommand_sudoAndEnv_recognizedAsSupported() {
        SessionRecord.SessionEvent event = new SessionRecord.SessionEvent(
            "cmd-3",
            Instant.now(),
            "sudo env CI=true npm test",
            0,
            150L,
            200L,
            "PASS test.js\n",
            false
        );

        SessionRecord session = new SessionRecord(
            "session-3",
            AgentTranscriptFormat.WINDSURF,
            Path.of("test.json"),
            Instant.now(),
            Instant.now(),
            "/workspace",
            List.of(event)
        );

        SessionIntelligenceReport report = service.analyze(List.of(session));

        assertThat(report.estimatedSavedTokens()).isPositive();
        assertThat(report.unsupportedCommands()).isEmpty();
    }
}
