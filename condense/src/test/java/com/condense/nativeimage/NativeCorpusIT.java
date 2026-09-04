package com.condense.nativeimage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native-image smoke that a catalog entry is filtered through the real binary
 * and real command dispatch, not a JVM-only {@code new PytestFilter()}.
 */
class NativeCorpusIT {

    @TempDir
    Path tempDir;

    @Test
    void stubbedPytestThroughNativeBinaryKeepsCriticalSignals() throws Exception {
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Path stubDir = tempDir.resolve("bin");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        Files.createDirectories(stubDir);

        byte[] fixture = loadClasspathFixture("/fixtures/pytest/typical.txt");
        Path fixtureFile = stubDir.resolve("fixture.txt");
        Files.write(fixtureFile, fixture);

        if (NativeBinarySupport.isWindows()) {
            Path cmd = stubDir.resolve("pytest.cmd");
            Files.writeString(cmd, "@echo off\r\ntype \"%~dp0fixture.txt\"\r\nexit /b 1\r\n",
                StandardCharsets.UTF_8);
        } else {
            Path script = stubDir.resolve("pytest");
            Files.writeString(script, "#!/bin/sh\ncat \"$(dirname \"$0\")/fixture.txt\"\nexit 1\n",
                StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException ignored) {
                script.toFile().setExecutable(true);
            }
        }

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir, dataDir, stubDir, "pytest"
        );

        assertThat(result.exitCode())
            .as("proxied pytest exit code must pass through: stdout=%s stderr=%s",
                result.stdout(), result.stderr())
            .isEqualTo(1);
        assertThat(result.stdout())
            .as("native dispatch must retain pytest critical signals: stdout=%s stderr=%s",
                result.stdout(), result.stderr())
            .contains("test_mul")
            .contains("failed");
    }

    private static byte[] loadClasspathFixture(String resource) throws Exception {
        try (var in = NativeCorpusIT.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource + " must be on the test classpath").isNotNull();
            return in.readAllBytes();
        }
    }
}
