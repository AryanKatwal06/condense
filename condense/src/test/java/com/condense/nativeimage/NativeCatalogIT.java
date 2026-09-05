package com.condense.nativeimage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native proof that a leftover catalog definition is dispatched inside the
 * shipped binary. Never skips.
 */
class NativeCatalogIT {

    @TempDir
    Path tempDir;

    @Test
    void stubbedMypyThroughNativeBinaryKeepsCriticalSignals() throws Exception {
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Path stubDir = tempDir.resolve("bin");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        Files.createDirectories(stubDir);

        byte[] fixture = loadClasspathFixture("/fixtures/mypy/typical.txt");
        Path fixtureFile = stubDir.resolve("fixture.txt");
        Files.write(fixtureFile, fixture);

        if (NativeBinarySupport.isWindows()) {
            Path cmd = stubDir.resolve("mypy.cmd");
            Files.writeString(cmd, "@echo off\r\ntype \"%~dp0fixture.txt\"\r\nexit /b 1\r\n",
                StandardCharsets.UTF_8);
        } else {
            Path script = stubDir.resolve("mypy");
            Files.writeString(script, "#!/bin/sh\ncat \"$(dirname \"$0\")/fixture.txt\"\nexit 1\n",
                StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException ignored) {
                script.toFile().setExecutable(true);
            }
        }

        NativeBinarySupport.CliResult json = NativeBinarySupport.run(
            configDir, dataDir, stubDir, "--format", "json", "mypy"
        );
        assertThat(json.exitCode()).isEqualTo(1);
        assertThat(json.stdout()).contains("\"kind\":\"opaque\"");

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir, dataDir, stubDir, "mypy"
        );

        assertThat(result.exitCode())
            .as("proxied mypy exit code must pass through: stdout=%s stderr=%s",
                result.stdout(), result.stderr())
            .isEqualTo(1);
        assertThat(result.stdout())
            .as("catalog host must filter in the native image: stdout=%s stderr=%s",
                result.stdout(), result.stderr())
            .startsWith("condense[filtered]")
            .contains("src/billing/invoice.py")
            .contains("src/auth/session.py");
    }

    private static byte[] loadClasspathFixture(String resource) throws Exception {
        try (var in = NativeCatalogIT.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource + " must be on the test classpath").isNotNull();
            return in.readAllBytes();
        }
    }
}
