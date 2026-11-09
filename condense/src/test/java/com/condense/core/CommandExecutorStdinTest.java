package com.condense.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Piped stdin delivery, EOF closure, broken-pipe resilience, and non-blocking
 * completion for short-lived children that ignore stdin.
 */
class CommandExecutorStdinTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void forwardsExplicitStdinThroughTheOverload() throws Exception {
        byte[] payload = "hello stdin\n".getBytes(StandardCharsets.UTF_8);

        ExecutionResult result = new CommandExecutor().execute(
            echoFirstLineCommand(),
            TIMEOUT,
            new ByteArrayInputStream(payload));

        assertThat(result.exitCode()).isZero();
        assertThat(result.readStdout()).contains("hello stdin");
    }

    @Test
    void closesChildStdinAtEofSoReadCommandsExit() throws Exception {
        // A command that waits for EOF before printing only completes when the
        // feeder closes the child stdin after the source reaches -1.
        ExecutionResult result = new CommandExecutor().execute(
            waitForEofCommand(),
            TIMEOUT,
            new ByteArrayInputStream("123456".getBytes(StandardCharsets.UTF_8)));

        assertThat(result.exitCode()).isZero();
        assertThat(result.readStdout()).contains("done");
    }

    @Test
    void largeStdinWithEarlyChildExitDoesNotFailOnBrokenPipe() throws Exception {
        byte[] payload = new byte[256 * 1024];
        Arrays.fill(payload, (byte) 'x');

        long start = System.nanoTime();
        ExecutionResult result = new CommandExecutor().execute(
            exitImmediatelyCommand(),
            TIMEOUT,
            new ByteArrayInputStream(payload));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.exitCode()).isZero();
        assertThat(elapsedMs).isLessThan(15_000);
    }

    @Test
    void idleEmptyStdinDoesNotDelayShortLivedChildren() throws Exception {
        long start = System.nanoTime();
        ExecutionResult result = new CommandExecutor().execute(
            exitImmediatelyCommand(),
            TIMEOUT,
            new ByteArrayInputStream(new byte[0]));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.exitCode()).isZero();
        assertThat(elapsedMs).isLessThan(15_000);
    }

    @Test
    void defaultSystemStdinDoesNotBlockAChildThatNeverReads() throws Exception {
        long start = System.nanoTime();
        ExecutionResult result = new CommandExecutor().execute(
            exitImmediatelyCommand(), TIMEOUT);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.exitCode()).isZero();
        assertThat(elapsedMs).isLessThan(15_000);
    }

    private static List<String> echoFirstLineCommand() {
        return WindowsCommandResolver.isWindows()
            ? List.of("cmd", "/c", "more")
            : List.of("sh", "-c", "read line; printf '%s' \"$line\"");
    }

    private static List<String> waitForEofCommand() {
        return WindowsCommandResolver.isWindows()
            ? List.of("cmd", "/c", "more >nul & echo done")
            : List.of("sh", "-c", "cat >/dev/null; echo done");
    }

    private static List<String> exitImmediatelyCommand() {
        return WindowsCommandResolver.isWindows()
            ? List.of("cmd", "/c", "exit 0")
            : List.of("sh", "-c", "exit 0");
    }
}
