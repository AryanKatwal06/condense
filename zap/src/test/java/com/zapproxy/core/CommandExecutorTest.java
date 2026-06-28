package com.zapproxy.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class CommandExecutorTest {

    @Inject
    CommandExecutor executor;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void capturesStdout() throws Exception {
        ExecutionResult r = executor.execute("echo", "hello zap");
        assertThat(r.exitCode()).isZero();
        assertThat(r.readStdout().trim()).isEqualTo("hello zap");
        assertThat(r.readStderr()).isBlank();
        assertThat(r.succeeded()).isTrue();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void propagatesNonZeroExitCode() throws Exception {
        // 'false' is a POSIX builtin that always exits 1
        ExecutionResult r = executor.execute("false");
        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(r.succeeded()).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void capturesStderrSeparately() throws Exception {
        ExecutionResult r = executor.execute("sh", "-c", "echo err >&2");
        assertThat(r.exitCode()).isZero();
        assertThat(r.readStdout()).isBlank();
        assertThat(r.readStderr().trim()).isEqualTo("err");
        assertThat(r.hasStderr()).isTrue();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void capturesBothStreamsConcurrently() throws Exception {
        // Writes to both stdout and stderr — validates deadlock prevention
        ExecutionResult r = executor.execute("sh", "-c", "echo out; echo err >&2");
        assertThat(r.readStdout().trim()).isEqualTo("out");
        assertThat(r.readStderr().trim()).isEqualTo("err");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void durationMsIsPositive() throws Exception {
        ExecutionResult r = executor.execute("echo", "timing");
        assertThat(r.durationMs()).isPositive();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void timeoutKillsSlowProcess() throws Exception {
        ExecutionResult r = executor.execute(
            List.of("sleep", "30"), Duration.ofMillis(200));
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.readStderr()).contains("timed out");
        assertThat(r.durationMs()).isLessThan(5_000);
    }

    @Test
    void emptyArgsThrowsIllegalArgument() {
        assertThatThrownBy(() -> executor.execute(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullArgsThrowsIllegalArgument() {
        assertThatThrownBy(() -> executor.execute((List<String>) null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
