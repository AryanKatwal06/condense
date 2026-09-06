package com.condense.nativeimage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native proof that the generated stage registry validates schema-1 overrides
 * and rejects unknown or ahead schemas without crashing.
 */
class NativeStageRegistryIT {

    @TempDir
    Path tempDir;

    @Test
    void schemaOneTailLinesAndRegexCaptureValidate() throws Exception {
        Path file = tempDir.resolve("valid.toml");
        Files.writeString(file, """
            schema_version = 1
            [filters."docker logs"]
            stages = [
              { strategy = "tail_lines", max_lines = 20, skip_blank = true },
              { strategy = "regex_capture", pattern = "(.*)", format = "$0" }
            ]
            """);
        NativeBinarySupport.CliResult result = run("config", "validate", "-f", file.toString());
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isZero();
        assertThat(result.stdout()).contains("is valid");
    }

    @Test
    void unknownStrategyIsReported() throws Exception {
        Path file = tempDir.resolve("unknown.toml");
        Files.writeString(file, """
            schema_version = 1
            [filters."ls"]
            stages = [ { strategy = "java.lang.Runtime" } ]
            """);
        NativeBinarySupport.CliResult result = run("config", "validate", "-f", file.toString());
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr() + result.stdout()).contains("Unknown strategy");
    }

    @Test
    void schemaAheadOverrideIsInvalid() throws Exception {
        Path file = tempDir.resolve("ahead.toml");
        Files.writeString(file, """
            schema_version = 2
            [filters."ls"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        NativeBinarySupport.CliResult result = run("config", "validate", "-f", file.toString());
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr() + result.stdout()).contains("schema_version");
    }

    private NativeBinarySupport.CliResult run(String... args) throws Exception {
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        return NativeBinarySupport.run(configDir, dataDir, args);
    }
}
