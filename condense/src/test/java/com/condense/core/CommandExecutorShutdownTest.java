package com.condense.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CommandExecutorShutdownTest {

    @Test
    void onStopDestroysARunningChild() throws Exception {
        CommandExecutor executor = new CommandExecutor();
        List<String> args = WindowsCommandResolver.isWindows()
            ? List.of("cmd", "/c", "ping -n 30 127.0.0.1 >nul")
            : List.of("sleep", "30");
        AtomicReference<ExecutionResult> holder = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                holder.set(executor.execute(args, Duration.ofSeconds(30)));
            } catch (Exception e) {
                error.set(e);
            }
        }, "condense-shutdown-test");
        worker.start();
        Process child = awaitActive(executor);
        executor.onStop(null);
        worker.join(15_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(error.get()).isNull();
        ExecutionResult result = holder.get();
        assertThat(result).isNotNull();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.termination()).isEqualTo(TerminationReason.DESTROYED);
        assertThat(child.isAlive()).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void onStopDestroysPosixGrandchild() throws Exception {
        CommandExecutor executor = new CommandExecutor();
        List<String> args = List.of("sh", "-c", "sleep 30");
        AtomicReference<ExecutionResult> holder = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                holder.set(executor.execute(args, Duration.ofSeconds(30)));
            } catch (Exception e) {
                error.set(e);
            }
        }, "condense-shutdown-grandchild-test");
        worker.start();
        Process child = awaitActive(executor);
        List<ProcessHandle> descendants = child.descendants().toList();
        executor.onStop(null);
        worker.join(15_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(error.get()).isNull();
        assertThat(holder.get()).isNotNull();
        assertThat(holder.get().termination()).isEqualTo(TerminationReason.DESTROYED);
        assertThat(child.isAlive()).isFalse();
        for (ProcessHandle descendant : descendants) {
            assertThat(descendant.isAlive()).isFalse();
        }
    }

    private static Process awaitActive(CommandExecutor executor) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Set<Process> active = executor.activeProcessesSnapshot();
            if (!active.isEmpty()) {
                Process process = active.iterator().next();
                if (process.isAlive()) {
                    return process;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("child never registered");
    }
}
