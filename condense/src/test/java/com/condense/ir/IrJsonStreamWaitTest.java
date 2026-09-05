package com.condense.ir;

import com.condense.core.CommandExecutor;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.StreamListener;
import com.condense.core.StreamingProxy;
import com.condense.filter.node.NpmInstallFilter;
import jakarta.enterprise.inject.Vetoed;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IrJsonStreamWaitTest {

    @Test
    void jsonFormatDoesNotLivePrintAndAlreadyPrintedIsFalse() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
        StreamingProxy.StreamedRun run = StreamingProxy.run(
            new FeedingExecutor(),
            new NpmInstallFilter(),
            List.of("npm", "install"),
            "npm install",
            CondenseConfig.defaults(),
            0,
            false,
            out,
            new PrintStream(OutputStream.nullOutputStream()),
            true
        );
        assertThat(run.alreadyPrinted()).isFalse();
        assertThat(run.filtered().document().kind()).isEqualTo(Document.DocumentKind.DEPENDENCY);
        assertThat(captured.toString(StandardCharsets.UTF_8)).isEmpty();
        Document.DependencyDocument payload =
            (Document.DependencyDocument) run.filtered().document().document();
        assertThat(payload.addedPackages()).isEqualTo(12);
        assertThat(payload.irrevocable()).anyMatch(line -> line.contains("npm warn"));
    }

    @Vetoed
    private static final class FeedingExecutor extends CommandExecutor {
        @Override
        public ExecutionResult execute(List<String> args, Duration timeout, StreamListener listener) {
            byte[] warn = "npm warn deprecated foo@1.0.0: gone\n".getBytes(StandardCharsets.UTF_8);
            byte[] added = "added 12 packages in 1s\n".getBytes(StandardCharsets.UTF_8);
            byte[] audit = "found 3 vulnerabilities\n".getBytes(StandardCharsets.UTF_8);
            listener.onStdout(warn, warn.length);
            listener.onStdout(added, added.length);
            listener.onStdout(audit, audit.length);
            return new ExecutionResult(0,
                "npm warn deprecated foo@1.0.0: gone\nadded 12 packages in 1s\nfound 3 vulnerabilities\n",
                "",
                5L);
        }
    }
}
