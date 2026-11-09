package com.condense.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the unfiltered escape hatch forwards both streams verbatim and keeps
 * the child exit code without consulting the filter registry.
 */
class ProxyServiceBypassTest {

    @Test
    void forwardsRawStdoutAndStderrAndPreservesExitCode() throws Exception {
        List<String> args = WindowsCommandResolver.isWindows()
            ? List.of("cmd", "/c", "echo raw-out & echo raw-err 1>&2 & exit /b 3")
            : List.of("sh", "-c", "echo raw-out; echo raw-err >&2; exit 3");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        ProxyService proxy = new ProxyService(new CommandExecutor(), null, null, null, null);

        ProxyService.Outcome outcome = proxy.bypass(
            args,
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8));

        try {
            assertThat(outcome.result().exitCode()).isEqualTo(3);
            assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("raw-out");
            assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("raw-err");
            assertThat(outcome.filtered().wasFiltered()).isFalse();
            assertThat(outcome.filtered().outTokens())
                .isEqualTo(outcome.filtered().rawTokens());
            assertThat(outcome.alreadyPrinted()).isTrue();
        } finally {
            outcome.result().cleanup();
        }
    }

    @Test
    void rejectsAnEmptyCommand() {
        ProxyService proxy = new ProxyService(new CommandExecutor(), null, null, null, null);

        assertThatThrownBy(() -> proxy.bypass(List.of(), System.out, System.err))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("command is required");
    }
}
