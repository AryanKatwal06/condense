package com.condense.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native-image proof that streaming mode is visible in explain and that a
 * PATH-stubbed npm install emits filtered progress through the shipped binary.
 */
class NativeStreamingIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateDirs() throws Exception {
        Files.createDirectories(configDir());
        Files.createDirectories(dataDir());
    }

    @Test
    void explainJsonNamesStreamModeForNpmInstall() throws Exception {
        Path fixture = tempDir.resolve("npm.txt");
        Files.writeString(fixture, "npm warn deprecated foo@1.0.0: gone\nadded 12 packages\nfound 1 vulnerabilit\n");
        NativeBinarySupport.CliResult explained = NativeBinarySupport.run(
            configDir(),
            dataDir(),
            "explain",
            "--input",
            fixture.toAbsolutePath().toString(),
            "--exit-code",
            "0",
            "--format",
            "json",
            "npm",
            "install"
        );
        assertThat(explained.exitCode())
            .as("stdout=%s stderr=%s", explained.stdout(), explained.stderr())
            .isZero();
        JsonNode report = JSON.readTree(explained.stdout());
        assertThat(report.get("pipeline_mode").asText()).isEqualTo("stream");
        assertThat(report.get("stages").isArray()).isTrue();
        boolean sawOrderLocal = false;
        for (JsonNode stage : report.get("stages")) {
            if ("order_local".equals(stage.path("streamability").asText())) {
                sawOrderLocal = true;
            }
        }
        assertThat(sawOrderLocal).isTrue();
    }

    @Test
    void pathStubbedNpmInstallKeepsWarnAndSummary() throws Exception {
        Path bin = tempDir.resolve("bin");
        Files.createDirectories(bin);
        if (NativeBinarySupport.isWindows()) {
            Files.writeString(bin.resolve("npm.cmd"),
                "@echo off\r\n"
                    + "echo npm warn deprecated foo@1.0.0: gone\r\n"
                    + "echo added 12 packages in 1s\r\n"
                    + "echo found 3 vulnerabilities\r\n"
                    + "exit /b 0\r\n");
        } else {
            Path stub = bin.resolve("npm");
            Files.writeString(stub,
                "#!/bin/sh\n"
                    + "echo 'npm warn deprecated foo@1.0.0: gone'\n"
                    + "echo 'added 12 packages in 1s'\n"
                    + "echo 'found 3 vulnerabilities'\n"
                    + "exit 0\n");
            stub.toFile().setExecutable(true);
        }

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), bin, "npm", "install");
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isZero();
        assertThat(result.stdout()).contains("npm warn deprecated foo");
        assertThat(result.stdout()).contains("✓ npm install: 12 packages");
        assertThat(result.stdout()).contains("condense[filtered]");
    }

    private Path configDir() {
        return tempDir.resolve("config");
    }

    private Path dataDir() {
        return tempDir.resolve("data");
    }
}
