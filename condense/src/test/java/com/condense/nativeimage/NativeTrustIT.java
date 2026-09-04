package com.condense.nativeimage;

import com.condense.trust.CiIndicator;
import com.condense.trust.TrustGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native proof that project overrides are skipped until trusted, that the CI
 * hatch cannot be armed by {@code CONDENSE_TRUST_PROJECT_FILTERS} alone, and
 * that filtered stdout carries a provenance stamp.
 */
class NativeTrustIT {

    @TempDir
    Path tempDir;

    @Test
    void untrustedProjectOverrideDoesNotDropFailed() throws Exception {
        Harness harness = Harness.create(tempDir.resolve("untrusted"));
        NativeBinarySupport.CliResult result = harness.runPytest();

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).contains("failed");
        assertThat(result.stdout()).contains("test_mul");
        assertThat(result.stdout()).doesNotContain("Skipping project filter override");
        assertThat(result.stderr()).contains("condense config trust");
    }

    @Test
    void trustedProjectOverrideCanApply() throws Exception {
        Harness harness = Harness.create(tempDir.resolve("trusted"));
        NativeBinarySupport.CliResult trust = NativeBinarySupport.run(
            harness.configDir, harness.dataDir, null, harness.workDir, null,
            "config", "trust", "--accept", "--grant", "reduce"
        );
        assertThat(trust.exitCode())
            .as("stdout=%s stderr=%s", trust.stdout(), trust.stderr())
            .isZero();

        NativeBinarySupport.CliResult result = harness.runPytest();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).doesNotContain("failed");
        assertThat(result.stdout()).startsWith("condense[filtered]");
    }

    @Test
    void hatchWithoutCiIndicatorStillSkips() throws Exception {
        Harness harness = Harness.create(tempDir.resolve("hatch"));
        Map<String, String> env = new LinkedHashMap<>();
        env.put(CiIndicator.TRUST_PROJECT_FILTERS, "1");
        for (String name : CiIndicator.CI_VARIABLES) {
            env.put(name, null);
        }

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            harness.configDir, harness.dataDir, harness.stubDir, harness.workDir, env, "pytest"
        );
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).contains("failed");
        assertThat(result.stderr()).contains("condense config trust");
    }

    @Test
    void filteredStdoutStartsWithProvenanceStamp() throws Exception {
        Harness harness = Harness.create(tempDir.resolve("stamp"));
        NativeBinarySupport.CliResult result = harness.runPytest();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains(TrustGate.SKIP_HINT);
        assertThat(result.stdout())
            .as("skip notices must stay on stderr so filtered stdout is stamped: stdout=%s stderr=%s",
                result.stdout(), result.stderr())
            .doesNotContain("Skipping project filter override")
            .startsWith("condense[filtered]");
    }

    private record Harness(Path configDir, Path dataDir, Path workDir, Path stubDir) {
        static Harness create(Path root) throws Exception {
            Path configDir = root.resolve("config");
            Path dataDir = root.resolve("data");
            Path workDir = root.resolve("project");
            Path stubDir = root.resolve("bin");
            Files.createDirectories(configDir);
            Files.createDirectories(dataDir);
            Files.createDirectories(workDir.resolve(".condense"));
            Files.createDirectories(stubDir);

            Files.writeString(workDir.resolve(".condense/filters.toml"), """
                schema_version = 1
                [filters."pytest"]
                stages = [
                  { strategy = "head_tail", head = 1, tail = 0 }
                ]
                """);

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
            return new Harness(configDir, dataDir, workDir, stubDir);
        }

        NativeBinarySupport.CliResult runPytest() throws Exception {
            return NativeBinarySupport.run(configDir, dataDir, stubDir, workDir, null, "pytest");
        }
    }

    private static byte[] loadClasspathFixture(String resource) throws Exception {
        try (var in = NativeTrustIT.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource + " must be on the test classpath").isNotNull();
            return in.readAllBytes();
        }
    }
}
