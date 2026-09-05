package com.condense.core;

import com.condense.filter.cloud.DockerBuildFilter;
import com.condense.filter.node.NpmInstallFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingTimingTest {

    @TempDir
    Path tempDir;

    @Test
    void npmWarnAppearsBeforeChildExits() throws Exception {
        Path sentinel = tempDir.resolve("npm-sleeping");
        String stub = writeNpmStub(sentinel);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
        CommandExecutor executor = new CommandExecutor();
        Thread runner = new Thread(() -> {
            try {
                StreamingProxy.run(
                    executor,
                    new NpmInstallFilter(),
                    List.of(stub, "install"),
                    "npm install",
                    CondenseConfig.defaults(),
                    0,
                    false,
                    out,
                    new PrintStream(OutputStream.nullOutputStream())
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "npm-stream");
        runner.start();
        waitFor(sentinel);
        String mid = captured.toString(StandardCharsets.UTF_8);
        assertThat(mid).contains("npm warn deprecated foo");
        assertThat(mid).doesNotContain("✓ npm install");
        runner.join(TimeUnit.SECONDS.toMillis(15));
        assertThat(runner.isAlive()).isFalse();
        String done = captured.toString(StandardCharsets.UTF_8);
        assertThat(done).contains("✓ npm install: 12 packages");
    }

    @Test
    void dockerStepAppearsBeforeChildExits() throws Exception {
        Path sentinel = tempDir.resolve("docker-sleeping");
        String stub = writeDockerStub(sentinel);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
        CommandExecutor executor = new CommandExecutor();
        Thread runner = new Thread(() -> {
            try {
                StreamingProxy.run(
                    executor,
                    new DockerBuildFilter(),
                    List.of(stub, "build", "."),
                    "docker build",
                    CondenseConfig.defaults(),
                    0,
                    false,
                    out,
                    new PrintStream(OutputStream.nullOutputStream())
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "docker-stream");
        runner.start();
        waitFor(sentinel);
        String mid = captured.toString(StandardCharsets.UTF_8);
        assertThat(mid).contains("#1 DONE");
        assertThat(mid).doesNotContain("✓ docker build");
        runner.join(TimeUnit.SECONDS.toMillis(15));
        assertThat(runner.isAlive()).isFalse();
        String done = captured.toString(StandardCharsets.UTF_8);
        assertThat(done).contains("✓ docker build: abcdef12");
    }

    private String writeNpmStub(Path sentinel) throws Exception {
        Path dir = tempDir.resolve("npm-bin");
        Files.createDirectories(dir);
        if (WindowsCommandResolver.isWindows()) {
            Path stub = dir.resolve("npm.cmd");
            Files.writeString(stub,
                "@echo off\r\n"
                    + "echo npm warn deprecated foo@1.0.0: gone\r\n"
                    + "echo.> \"" + sentinel.toAbsolutePath() + "\"\r\n"
                    + "ping -n 3 127.0.0.1 >nul\r\n"
                    + "echo added 12 packages in 1s\r\n"
                    + "echo found 3 vulnerabilities\r\n"
                    + "exit /b 0\r\n");
            return stub.toAbsolutePath().toString();
        }
        Path stub = dir.resolve("npm");
        Files.writeString(stub,
            "#!/bin/sh\n"
                + "echo 'npm warn deprecated foo@1.0.0: gone'\n"
                + "touch '" + sentinel.toAbsolutePath() + "'\n"
                + "sleep 1\n"
                + "echo 'added 12 packages in 1s'\n"
                + "echo 'found 3 vulnerabilities'\n"
                + "exit 0\n");
        stub.toFile().setExecutable(true);
        return stub.toAbsolutePath().toString();
    }

    private String writeDockerStub(Path sentinel) throws Exception {
        Path dir = tempDir.resolve("docker-bin");
        Files.createDirectories(dir);
        if (WindowsCommandResolver.isWindows()) {
            Path stub = dir.resolve("docker.cmd");
            Files.writeString(stub,
                "@echo off\r\n"
                    + "echo #1 DONE 0.1s\r\n"
                    + "echo.> \"" + sentinel.toAbsolutePath() + "\"\r\n"
                    + "ping -n 3 127.0.0.1 >nul\r\n"
                    + "echo Successfully built abcdef12\r\n"
                    + "echo Successfully tagged app:latest\r\n"
                    + "exit /b 0\r\n");
            return stub.toAbsolutePath().toString();
        }
        Path stub = dir.resolve("docker");
        Files.writeString(stub,
            "#!/bin/sh\n"
                + "echo '#1 DONE 0.1s'\n"
                + "touch '" + sentinel.toAbsolutePath() + "'\n"
                + "sleep 1\n"
                + "echo 'Successfully built abcdef12'\n"
                + "echo 'Successfully tagged app:latest'\n"
                + "exit 0\n");
        stub.toFile().setExecutable(true);
        return stub.toAbsolutePath().toString();
    }

    private static void waitFor(Path sentinel) throws Exception {
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(sentinel)) {
                Thread.sleep(80);
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("stub never reached its sleep sentinel: " + sentinel);
    }
}
