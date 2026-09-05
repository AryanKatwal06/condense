package com.condense.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingBrokenPipeTest {

    @Test
    void brokenConsumerPipeStillReapsChild() throws Exception {
        List<String> args = WindowsCommandResolver.isWindows()
            ? List.of("cmd", "/c", "echo L1& echo L2& echo L3& echo L4& echo L5& echo L6& exit /b 0")
            : List.of("sh", "-c", "echo L1; echo L2; echo L3; echo L4; echo L5; echo L6; exit 0");
        AtomicInteger writes = new AtomicInteger();
        PrintStream broken = new PrintStream(new BreakAfter(writes, 2), true, StandardCharsets.UTF_8);
        StreamingProxy.StreamedRun run = StreamingProxy.run(
            new CommandExecutor(),
            new PassthroughStrategy(),
            args,
            "echo-lines",
            CondenseConfig.defaults(),
            0,
            false,
            broken,
            new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertThat(broken.checkError()).isTrue();
        assertThat(writes.get()).isGreaterThan(2);
        assertThat(run.result().exitCode()).isEqualTo(0);
        assertThat(run.result().termination()).isEqualTo(TerminationReason.CHILD_EXIT);
        assertThat(run.result().durationMs()).isLessThan(Duration.ofSeconds(10).toMillis());
        assertThat(run.result().readStdout()).contains("L6");
    }

    private static final class BreakAfter extends OutputStream {
        private final AtomicInteger writes;
        private final int failAfter;

        BreakAfter(AtomicInteger writes, int failAfter) {
            this.writes = writes;
            this.failAfter = failAfter;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int n = writes.incrementAndGet();
            if (n > failAfter) {
                throw new IOException("broken pipe");
            }
        }
    }
}
