package com.condense.filter.pipeline.config;

import com.condense.core.PlatformDirs;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.RegexTimeoutException;
import com.condense.filter.strategy.TimeoutCharSequence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FilterOverrideSecurityAdversarialTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Attack 1: Arbitrary Java class name in strategy is rejected")
    void testArbitraryClassInjectionRejected() throws IOException {
        Path projectDir = tempDir.resolve("exploit-class-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String maliciousToml = """
            schema_version = 1
            [filters."exploit"]
            stages = [
              { strategy = "java.lang.Runtime" },
              { strategy = "com.condense.core.CommandExecutor" },
              { strategy = "java.lang.ProcessBuilder" }
            ]
            """;
        Path overrideFile = condenseDir.resolve("filters.toml");
        Files.writeString(overrideFile, maliciousToml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterOverrideValidationResult result = loader.validateFile(overrideFile, projectDir);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FilterOverrideValidationResult.Status.SEMANTIC_ERROR);
        assertThat(result.errors()).anyMatch(e -> e.contains("Unknown strategy: 'java.lang.Runtime'"));
        assertThat(result.errors()).anyMatch(e -> e.contains("Unknown strategy: 'com.condense.core.CommandExecutor'"));

        // Fail-open: loader refuses to execute exploit and uses default pipeline
        FilterPipeline defaultPipeline = FilterPipeline.of((in, ctx) -> StageResult.continueWith("SAFE"));
        FilterPipeline resolved = loader.resolvePipeline("exploit", defaultPipeline, projectDir);
        assertThat(resolved).isSameAs(defaultPipeline);
    }

    @Test
    @DisplayName("Attack 2: Symlink escape pointing outside project tree is rejected with SECURITY_VIOLATION")
    void testSymlinkEscapeRejected() throws IOException {
        Path projectDir = tempDir.resolve("project-root");
        Path outsideDir = tempDir.resolve("outside-forbidden");
        Files.createDirectories(projectDir.resolve(".condense"));
        Files.createDirectories(outsideDir);

        Path outsideFile = outsideDir.resolve("malicious.toml");
        Files.writeString(outsideFile, """
            schema_version = 1
            [filters."ls"]
            stages = [ { strategy = "ansi_strip" } ]
            """);

        Path symlinkTarget = projectDir.resolve(".condense/filters.toml");
        try {
            Files.createSymbolicLink(symlinkTarget, outsideFile);
        } catch (UnsupportedOperationException | IOException e) {
            // Symlinks may not be supported or permitted on certain Windows environments without elevated privileges
            return;
        }

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterOverrideValidationResult result = loader.validateFile(symlinkTarget, projectDir);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FilterOverrideValidationResult.Status.SECURITY_VIOLATION);
        assertThat(result.errors()).anyMatch(e -> e.contains("resolves outside expected directory"));

        // Loader falls back safely
        FilterPipeline defaultPipeline = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));
        FilterPipeline resolved = loader.resolvePipeline("ls", defaultPipeline, projectDir);
        assertThat(resolved).isSameAs(defaultPipeline);
    }

    @Test
    @DisplayName("Attack 3: Negative or out-of-bounds parameter values are rejected")
    void testOutOfBoundsParametersRejected() throws IOException {
        Path projectDir = tempDir.resolve("bad-params-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String badParamsToml = """
            schema_version = 1
            [filters."negative-window"]
            stages = [
              { strategy = "deduplication", window_size = -50 }
            ]

            [filters."zero-window"]
            stages = [
              { strategy = "deduplication", window_size = 0 }
            ]

            [filters."excessive-window"]
            stages = [
              { strategy = "deduplication", window_size = 999999 }
            ]
            """;
        Path overrideFile = condenseDir.resolve("filters.toml");
        Files.writeString(overrideFile, badParamsToml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterOverrideValidationResult result = loader.validateFile(overrideFile, projectDir);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FilterOverrideValidationResult.Status.SEMANTIC_ERROR);
        assertThat(result.errors()).anyMatch(e -> e.contains("'window_size' must be between 1 and 10000, got: -50"));
        assertThat(result.errors()).anyMatch(e -> e.contains("'window_size' must be between 1 and 10000, got: 0"));
        assertThat(result.errors()).anyMatch(e -> e.contains("'window_size' must be between 1 and 10000, got: 999999"));
    }

    @Test
    @DisplayName("Attack 4: Malformed regex patterns are caught safely")
    void testMalformedRegexPatternRejected() throws IOException {
        Path projectDir = tempDir.resolve("bad-regex-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String badRegexToml = """
            schema_version = 1
            [filters."bad-grouping"]
            stages = [
              { strategy = "grouping", pattern = "[unclosed(pattern" }
            ]
            """;
        Path overrideFile = condenseDir.resolve("filters.toml");
        Files.writeString(overrideFile, badRegexToml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterOverrideValidationResult result = loader.validateFile(overrideFile, projectDir);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FilterOverrideValidationResult.Status.SEMANTIC_ERROR);
        assertThat(result.errors()).anyMatch(e -> e.contains("Invalid regex in 'pattern'"));
    }

    @Test
    @DisplayName("Attack 5: Grouping regex without capture group is rejected")
    void testGroupingPatternWithoutCaptureGroupRejected() throws IOException {
        Path projectDir = tempDir.resolve("no-group-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String noCaptureToml = """
            schema_version = 1
            [filters."no-capture"]
            stages = [
              { strategy = "grouping", pattern = "no_capture_groups_here" }
            ]
            """;
        Path overrideFile = condenseDir.resolve("filters.toml");
        Files.writeString(overrideFile, noCaptureToml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterOverrideValidationResult result = loader.validateFile(overrideFile, projectDir);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FilterOverrideValidationResult.Status.SEMANTIC_ERROR);
        assertThat(result.errors()).anyMatch(e -> e.contains("regex must contain at least one capture group"));
    }

    @Test
    @DisplayName("Attack 6: Invalid state machine actions are rejected")
    void testInvalidStateMachineActionRejected() throws IOException {
        Path projectDir = tempDir.resolve("bad-action-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String badActionToml = """
            schema_version = 1
            [filters."bad-sm"]
            stages = [
              { strategy = "state_machine", initial_state = "INIT", transitions = [{ from_state = "INIT", pattern = "^.*$", action = "EXECUTE_MALICIOUS_CODE", next_state = "INIT" }], default_actions = { INIT = "DESTROY" } }
            ]
            """;
        Path overrideFile = condenseDir.resolve("filters.toml");
        Files.writeString(overrideFile, badActionToml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterOverrideValidationResult result = loader.validateFile(overrideFile, projectDir);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FilterOverrideValidationResult.Status.SEMANTIC_ERROR);
        assertThat(result.errors()).anyMatch(e -> e.contains("Invalid 'action': 'EXECUTE_MALICIOUS_CODE'"));
        assertThat(result.errors()).anyMatch(e -> e.contains("Invalid action: 'DESTROY'"));
    }

    @Test
    @DisplayName("Attack 7: Binary garbage payload in filters.toml is safely rejected without crash")
    void testBinaryGarbagePayloadFailsOpen() throws IOException {
        Path projectDir = tempDir.resolve("garbage-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        byte[] garbage = new byte[] { (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 0x00, 0x1F, 0x7F };
        Path overrideFile = condenseDir.resolve("filters.toml");
        Files.write(overrideFile, garbage);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterOverrideValidationResult result = loader.validateFile(overrideFile, projectDir);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FilterOverrideValidationResult.Status.SYNTAX_ERROR);

        // Fail-open check
        FilterPipeline defaultPipeline = FilterPipeline.of((in, ctx) -> StageResult.continueWith("SAFE_PASSTHROUGH"));
        FilterPipeline resolved = loader.resolvePipeline("npm install", defaultPipeline, projectDir);
        assertThat(resolved).isSameAs(defaultPipeline);
        assertThat(resolved.execute("raw text")).isEqualTo("SAFE_PASSTHROUGH");
    }

    @Test
    @DisplayName("Attack 8: Catastrophic backtracking in grouping pattern aborts within bounded timeout and fails open")
    void testGroupingCatastrophicBacktrackingAbortsWithinTimeout() throws IOException {
        Path projectDir = tempDir.resolve("redos-grouping-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        // Pattern (a*)a*a*a*a*a*a*b exhibits genuine catastrophic backtracking on a...ac
        String redosToml = """
            schema_version = 1
            [filters."redos-group"]
            stages = [
              { strategy = "grouping", pattern = "(a*)a*a*a*a*a*a*b" }
            ]
            """;
        Path overrideFile = condenseDir.resolve("filters.toml");
        Files.writeString(overrideFile, redosToml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterOverrideValidationResult result = loader.validateFile(overrideFile, projectDir);
        assertThat(result.isValid()).isTrue();

        FilterPipeline defaultPipeline = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT_FAILOPEN"));
        FilterPipeline resolved = loader.resolvePipeline("redos-group", defaultPipeline, projectDir);
        assertThat(resolved).isNotSameAs(defaultPipeline);

        // Crafted input: 34 'a' characters followed by 'c'
        // In unmitigated backtracking, this pattern explores tens of millions of states
        String adversarialInput = "a".repeat(34) + "c";

        long start = System.nanoTime();
        String output = resolved.execute(adversarialInput);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        // Bounded time limit assertion: must abort cleanly around ~200ms and well under 2000ms
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(100L).isLessThan(2000L);

        // Fail-open guarantee: stage exception caught by pipeline, returning intermediate text
        assertThat(output).isEqualTo(adversarialInput);
    }

    @Test
    @DisplayName("Attack 9: Catastrophic backtracking in state machine pattern aborts within bounded timeout and fails open")
    void testStateMachineCatastrophicBacktrackingAbortsWithinTimeout() throws IOException {
        Path projectDir = tempDir.resolve("redos-sm-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String redosSmToml = """
            schema_version = 1
            [filters."redos-sm"]
            stages = [
              { strategy = "state_machine", initial_state = "START", transitions = [
                { from_state = "START", pattern = "a*a*a*a*a*a*a*b", action = "EMIT", next_state = "MATCHED" }
              ] }
            ]
            """;
        Path overrideFile = condenseDir.resolve("filters.toml");
        Files.writeString(overrideFile, redosSmToml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterOverrideValidationResult result = loader.validateFile(overrideFile, projectDir);
        assertThat(result.isValid()).isTrue();

        FilterPipeline defaultPipeline = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT_FAILOPEN"));
        FilterPipeline resolved = loader.resolvePipeline("redos-sm", defaultPipeline, projectDir);
        assertThat(resolved).isNotSameAs(defaultPipeline);

        String adversarialInput = "a".repeat(34) + "c";

        long start = System.nanoTime();
        String output = resolved.execute(adversarialInput);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        // Bounded time limit assertion
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(100L).isLessThan(2000L);

        // Fail-open guarantee
        assertThat(output).isEqualTo(adversarialInput);
    }

    @Test
    @DisplayName("Attack 10: Ordinary non-pathological regex patterns continue to execute without false timeouts")
    void testLegitimateRegexPatternsExecuteNormally() throws IOException {
        Path projectDir = tempDir.resolve("safe-regex-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String safeToml = """
            schema_version = 1
            [filters."safe-logs"]
            stages = [
              { strategy = "grouping", pattern = '^\\[(INFO|WARN|ERROR)\\]\\s+(.*)$' }
            ]
            """;
        Path overrideFile = condenseDir.resolve("filters.toml");
        Files.writeString(overrideFile, safeToml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterPipeline defaultPipeline = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));
        FilterPipeline resolved = loader.resolvePipeline("safe-logs", defaultPipeline, projectDir);
        assertThat(resolved).isNotSameAs(defaultPipeline);

        StringBuilder logBuilder = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            logBuilder.append("[INFO] Service worker heartbeat ").append(i).append("\n");
            logBuilder.append("[WARN] High latency detected ").append(i).append("\n");
        }
        String input = logBuilder.toString();

        long start = System.nanoTime();
        String output = resolved.execute(input);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMillis).isLessThan(500L);
        assertThat(output).contains("INFO").contains("WARN").contains("50");
    }

    @Test
    @DisplayName("Attack 11: Static complexity budgets reject oversized patterns and excessive transitions")
    void testStaticComplexityBudgetsRejected() throws IOException {
        Path projectDir = tempDir.resolve("oversized-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String longPattern = "a".repeat(501);
        StringBuilder transitions = new StringBuilder();
        for (int i = 0; i < 55; i++) {
            transitions.append(String.format(
                "{ from_state = 'S%d', pattern = 'foo%d', action = 'EMIT', next_state = 'S%d' },",
                i, i, i + 1));
        }

        String oversizedToml = String.format("""
            schema_version = 1
            [filters."oversized-pattern"]
            stages = [
              { strategy = "grouping", pattern = "(%s)" }
            ]

            [filters."oversized-transitions"]
            stages = [
              { strategy = "state_machine", initial_state = "S0", transitions = [%s] }
            ]
            """, longPattern, transitions);

        Path overrideFile = condenseDir.resolve("filters.toml");
        Files.writeString(overrideFile, oversizedToml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterOverrideValidationResult result = loader.validateFile(overrideFile, projectDir);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FilterOverrideValidationResult.Status.SEMANTIC_ERROR);
        assertThat(result.errors()).anyMatch(e -> e.contains("'pattern' regex exceeds maximum allowed length of 500 characters"));
        assertThat(result.errors()).anyMatch(e -> e.contains("'transitions' count exceeds maximum allowed limit of 50"));

        // Fail-open
        FilterPipeline defaultPipeline = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));
        FilterPipeline resolvedPattern = loader.resolvePipeline("oversized-pattern", defaultPipeline, projectDir);
        FilterPipeline resolvedTransitions = loader.resolvePipeline("oversized-transitions", defaultPipeline, projectDir);
        assertThat(resolvedPattern).isSameAs(defaultPipeline);
        assertThat(resolvedTransitions).isSameAs(defaultPipeline);
    }
}
