package com.condense.nativeimage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native proof of the proxy reliability contract. Never skips.
 */
class NativeReliabilityIT {

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateDirs() throws Exception {
        Files.createDirectories(configDir());
        Files.createDirectories(dataDir());
    }

    @Test
    void timeoutKeepsPriorStderr() throws Exception {
        Path stubDir = tempDir.resolve("bin");
        Files.createDirectories(stubDir);
        if (NativeBinarySupport.isWindows()) {
            Files.writeString(stubDir.resolve("sleepy.cmd"),
                "@echo off\r\n"
                    + "echo prior-stdout\r\n"
                    + "echo prior-stderr 1>&2\r\n"
                    + "ping -n 20 127.0.0.1 >nul\r\n"
                    + "exit /b 0\r\n",
                StandardCharsets.UTF_8);
        } else {
            Path script = stubDir.resolve("sleepy");
            Files.writeString(script,
                "#!/bin/sh\n"
                    + "echo prior-stdout\n"
                    + "echo prior-stderr >&2\n"
                    + "sleep 20\n"
                    + "exit 0\n",
                StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException ignored) {
                script.toFile().setExecutable(true);
            }
        }

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(),
            dataDir(),
            stubDir,
            null,
            Map.of(com.condense.core.CommandExecutor.TIMEOUT_ENV, "1"),
            "sleepy"
        );
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isEqualTo(-1);
        assertThat(result.stderr()).contains("prior-stderr");
        assertThat(result.stderr()).contains("timed out");
        assertThat(result.stdout()).contains("prior-stdout");
    }

    @Test
    void outputCapPrintsBanner() throws Exception {
        Path blob = tempDir.resolve("oversize.txt");
        byte[] meg = new byte[1024 * 1024];
        Arrays.fill(meg, (byte) 'A');
        try (var out = Files.newOutputStream(blob)) {
            for (int i = 0; i < 12; i++) {
                out.write(meg);
                out.write('\n');
            }
        }
        NativeBinarySupport.CliResult result;
        if (NativeBinarySupport.isWindows()) {
            result = NativeBinarySupport.run(
                configDir(),
                dataDir(),
                "cmd",
                "/c",
                "type \"" + blob.toAbsolutePath() + "\" & exit /b 7"
            );
        } else {
            result = NativeBinarySupport.run(
                configDir(),
                dataDir(),
                "sh",
                "-c",
                "cat '" + blob.toAbsolutePath() + "'; exit 7"
            );
        }
        assertThat(result.stderr())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .contains("condense: output capped at 10MB");
        assertThat(result.exitCode()).isIn(-1, 1, 7);
    }

    @Test
    void proxiedExitSevenStaysSeven() throws Exception {
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(),
            dataDir(),
            NativeBinarySupport.exitCodeCommand(7)
        );
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isEqualTo(7);
    }

    private Path configDir() {
        return tempDir.resolve("config");
    }

    private Path dataDir() {
        return tempDir.resolve("data");
    }
}
