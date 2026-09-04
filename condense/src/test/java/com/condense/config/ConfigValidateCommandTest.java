package com.condense.config;

import com.condense.core.PlatformDirs;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigValidateCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Validation succeeds and returns 0 when no override files exist")
    void testValidateWhenFilesNotPresent() {
        PlatformDirs platformDirs = new PlatformDirs() {
            @Override
            public Path resolveConfigDir() {
                return tempDir.resolve("no_config");
            }
        };

        FilterOverrideLoader loader = new FilterOverrideLoader(platformDirs);
        ConfigValidateCommand cmd = new ConfigValidateCommand(loader);

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(outBytes));
            int exitCode = cmd.call();
            assertThat(exitCode).isEqualTo(0);
            assertThat(outBytes.toString()).contains("(not present)");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("Validation succeeds with explicit valid file flag")
    void testValidateExplicitValidFile() throws Exception {
        Path validFile = tempDir.resolve("valid-filters.toml");
        String toml = """
            schema_version = 1
            [filters."ls"]
            stages = [
              { strategy = "tree_compression" }
            ]

            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" },
              { strategy = "deduplication", window_size = 20 }
            ]
            """;
        Files.writeString(validFile, toml);

        ConfigValidateCommand cmd = new ConfigValidateCommand();
        cmd.explicitFile = validFile;

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(outBytes));
            int exitCode = cmd.call();
            assertThat(exitCode).isEqualTo(0);
            assertThat(outBytes.toString()).contains("is valid (2 filter overrides defined)");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("Validation fails and returns 1 with explicit invalid file flag")
    void testValidateExplicitInvalidFile() throws Exception {
        Path invalidFile = tempDir.resolve("invalid-filters.toml");
        String toml = """
            schema_version = 1
            [filters."bad-cmd"]
            stages = [
              { strategy = "unknown_strategy_name" }
            ]
            """;
        Files.writeString(invalidFile, toml);

        ConfigValidateCommand cmd = new ConfigValidateCommand();
        cmd.explicitFile = invalidFile;

        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(errBytes));
            int exitCode = cmd.call();
            assertThat(exitCode).isEqualTo(1);
            assertThat(errBytes.toString()).contains("has semantic validation errors:");
            assertThat(errBytes.toString()).contains("Unknown strategy: 'unknown_strategy_name'");
        } finally {
            System.setErr(originalErr);
        }
    }
}
