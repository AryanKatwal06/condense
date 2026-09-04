package com.condense.nativeimage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NativeCliIT {

    @TempDir
    Path tempDir;

    @Test
    void versionExitsZeroWithOutput() throws Exception {
        NativeBinarySupport.CliResult result = run("--version");
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout() + result.stderr()).isNotBlank();
    }

    @Test
    void helpExitsZeroWithOutput() throws Exception {
        NativeBinarySupport.CliResult result = run("--help");
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout() + result.stderr()).containsIgnoringCase("condense");
    }

    @Test
    void proxiedExitCodeIsPassedThrough() throws Exception {
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), NativeBinarySupport.exitCodeCommand(42)
        );
        assertThat(result.exitCode()).isEqualTo(42);
    }

    @Test
    void configValidateWithMissingOverridesFailsOpen() throws Exception {
        NativeBinarySupport.CliResult result = run("config", "validate");
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).contains("(not present)");
    }

    private NativeBinarySupport.CliResult run(String... args) throws Exception {
        return NativeBinarySupport.run(configDir(), dataDir(), args);
    }

    private Path configDir() {
        return tempDir.resolve("config");
    }

    private Path dataDir() {
        return tempDir.resolve("data");
    }
}
