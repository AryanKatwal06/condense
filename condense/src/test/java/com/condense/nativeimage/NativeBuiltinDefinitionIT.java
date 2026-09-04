package com.condense.nativeimage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native proof that schema v1 override validation and promoted stages
 * are available inside the shipped binary.
 */
class NativeBuiltinDefinitionIT {

    @TempDir
    Path tempDir;

    @Test
    void validTailLinesOverrideValidates() throws Exception {
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        Path file = tempDir.resolve("valid.toml");
        Files.writeString(file, """
            schema_version = 1
            [filters."docker logs"]
            stages = [ { strategy = "tail_lines", max_lines = 20, skip_blank = true } ]
            """);

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir, dataDir, "config", "validate", "-f", file.toString()
        );
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isZero();
        assertThat(result.stdout()).contains("is valid");
    }

    @Test
    void missingSchemaVersionAndUnknownKeyFailValidate() throws Exception {
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);

        Path missingVersion = tempDir.resolve("no-version.toml");
        Files.writeString(missingVersion, """
            [filters."ls"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        NativeBinarySupport.CliResult missing = NativeBinarySupport.run(
            configDir, dataDir, "config", "validate", "-f", missingVersion.toString()
        );
        assertThat(missing.exitCode()).isEqualTo(1);
        assertThat(missing.stderr()).contains("schema_version");

        Path unknownKey = tempDir.resolve("unknown.toml");
        Files.writeString(unknownKey, """
            schema_version = 1
            extra = true
            [filters."ls"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        NativeBinarySupport.CliResult unknown = NativeBinarySupport.run(
            configDir, dataDir, "config", "validate", "-f", unknownKey.toString()
        );
        assertThat(unknown.exitCode()).isEqualTo(1);
        assertThat(unknown.stderr()).contains("Unknown key");
        assertThat(unknown.stderr()).containsAnyOf("extra", "line");
    }
}
