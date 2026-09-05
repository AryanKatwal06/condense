package com.condense.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutputCapAndTimeoutTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveProxyTimeoutTreatsBlankAndNonPositiveAsWaitForever() {
        assertThat(CommandExecutor.resolveProxyTimeout(null)).isEqualTo(Duration.ZERO);
        assertThat(CommandExecutor.resolveProxyTimeout("")).isEqualTo(Duration.ZERO);
        assertThat(CommandExecutor.resolveProxyTimeout("  ")).isEqualTo(Duration.ZERO);
        assertThat(CommandExecutor.resolveProxyTimeout("0")).isEqualTo(Duration.ZERO);
        assertThat(CommandExecutor.resolveProxyTimeout("-3")).isEqualTo(Duration.ZERO);
        assertThat(CommandExecutor.resolveProxyTimeout("nope")).isEqualTo(Duration.ZERO);
        assertThat(CommandExecutor.resolveProxyTimeout("5")).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void streamAboveTenMegabytesPrintsCapAndKeepsChildExit() throws Exception {
        Path blob = tempDir.resolve("oversize.txt");
        byte[] meg = new byte[1024 * 1024];
        Arrays.fill(meg, (byte) 'A');
        try (OutputStream out = Files.newOutputStream(blob)) {
            for (int i = 0; i < 12; i++) {
                out.write(meg);
                out.write('\n');
            }
        }
        List<String> args = WindowsCommandResolver.isWindows()
            ? List.of("cmd", "/c", "type \"" + blob.toAbsolutePath() + "\" & exit /b 7")
            : List.of("sh", "-c", "cat '" + blob.toAbsolutePath() + "'; exit 7");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        StreamingProxy.StreamedRun run = StreamingProxy.run(
            new CommandExecutor(),
            new PassthroughStrategy(),
            args,
            "type-oversize",
            CondenseConfig.defaults(),
            0,
            false,
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );

        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("condense: output capped at 10MB");
        assertThat(run.result().exitCode()).isIn(-1, 7);
        assertThat(run.result().termination()).isEqualTo(TerminationReason.OUTPUT_CAP);
    }

    @Test
    void stderrAboveTenMegabytesCapsIndependently() throws Exception {
        Path blob = tempDir.resolve("oversize-err.txt");
        byte[] meg = new byte[1024 * 1024];
        Arrays.fill(meg, (byte) 'B');
        try (OutputStream out = Files.newOutputStream(blob)) {
            for (int i = 0; i < 12; i++) {
                out.write(meg);
                out.write('\n');
            }
        }
        List<String> args = WindowsCommandResolver.isWindows()
            ? List.of("cmd", "/c", "type \"" + blob.toAbsolutePath() + "\" 1>&2 & exit /b 7")
            : List.of("sh", "-c", "cat '" + blob.toAbsolutePath() + "' >&2; exit 7");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        StreamingProxy.StreamedRun run = StreamingProxy.run(
            new CommandExecutor(),
            new PassthroughStrategy(),
            args,
            "type-oversize-stderr",
            CondenseConfig.defaults(),
            0,
            false,
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );

        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("condense: output capped at 10MB");
        assertThat(run.result().termination()).isEqualTo(TerminationReason.OUTPUT_CAP);
        assertThat(run.result().exitCode()).isIn(-1, 1, 7);
    }

    @Test
    void shortTimeoutKillsASleepingChild() throws Exception {
        List<String> args = WindowsCommandResolver.isWindows()
            ? List.of("cmd", "/c", "ping -n 20 127.0.0.1 >nul")
            : List.of("sleep", "20");
        ExecutionResult result = new CommandExecutor().execute(args, Duration.ofMillis(400));
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.termination()).isEqualTo(TerminationReason.TIMEOUT);
        assertThat(result.readStderr()).contains("timed out");
        assertThat(result.durationMs()).isLessThan(8_000);
    }
}
