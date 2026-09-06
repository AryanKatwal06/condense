package com.condense.nativeimage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native proof that every indexed catalog definition dispatches inside the
 * shipped binary. Never skips. Optional {@code condense.native.catalog.shard=i/n}
 * selects a hash shard.
 */
class NativeCatalogMatrixIT {

    @TempDir
    Path tempDir;

    @Test
    void everyMatrixRowKeepsACriticalSignalThroughTheNativeBinary() throws Exception {
        List<NativeCatalogMatrixSupport.Row> rows = NativeCatalogMatrixSupport.shard(
            NativeCatalogMatrixSupport.load(),
            System.getProperty("condense.native.catalog.shard")
        );
        assertThat(rows).isNotEmpty();

        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);

        for (NativeCatalogMatrixSupport.Row row : rows) {
            Path stubDir = tempDir.resolve("bin-" + row.definition());
            Files.createDirectories(stubDir);
            byte[] fixture = loadClasspathFixture("/" + row.fixture());
            Files.write(stubDir.resolve("fixture.txt"), fixture);

            String firstToken = row.command().trim().split("\\s+")[0];
            if (NativeBinarySupport.isWindows()) {
                Files.writeString(
                    stubDir.resolve(firstToken + ".cmd"),
                    "@echo off\r\ntype \"%~dp0fixture.txt\"\r\nexit /b " + row.exitCode() + "\r\n",
                    StandardCharsets.UTF_8
                );
            } else {
                Path script = stubDir.resolve(firstToken);
                Files.writeString(
                    script,
                    "#!/bin/sh\ncat \"$(dirname \"$0\")/fixture.txt\"\nexit " + row.exitCode() + "\n",
                    StandardCharsets.UTF_8
                );
                try {
                    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
                } catch (UnsupportedOperationException ignored) {
                    script.toFile().setExecutable(true);
                }
            }

            String[] args = row.command().trim().split("\\s+");
            NativeBinarySupport.CliResult result = NativeBinarySupport.run(
                configDir, dataDir, stubDir, args
            );
            assertThat(result.exitCode())
                .as("%s proxied exit: stdout=%s stderr=%s", row.definition(), result.stdout(), result.stderr())
                .isEqualTo(row.exitCode());
            assertThat(result.stdout())
                .as("%s must keep a critical signal: stdout=%s", row.definition(), result.stdout())
                .contains(row.mustContain().get(0));
            String fixtureText = new String(fixture, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String stdoutText = result.stdout() == null ? "" : result.stdout().replace("\r\n", "\n");
            if (!stdoutText.equals(fixtureText)) {
                assertThat(result.stdout())
                    .as("%s compressed output must carry the filtered stamp: stdout=%s",
                        row.definition(), result.stdout())
                    .contains("condense[filtered]");
            }
        }
    }

    private static byte[] loadClasspathFixture(String resource) throws Exception {
        try (var in = NativeCatalogMatrixIT.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource + " must be on the test classpath").isNotNull();
            return in.readAllBytes();
        }
    }
}
