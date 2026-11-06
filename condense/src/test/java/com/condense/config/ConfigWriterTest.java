package com.condense.config;

import com.condense.core.ConfigLoader;
import com.condense.core.PlatformDirs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigWriterTest {

    @TempDir
    Path tempDir;

    private ConfigWriter writer;

    @BeforeEach
    void setUp() {
        PlatformDirs dirs = new PlatformDirs() {
            @Override
            public Path getConfigFile() {
                return tempDir.resolve("config.toml");
            }
        };
        writer = new ConfigWriter(dirs, new ConfigLoader(dirs));
    }

    @Test
    void toTomlString_isNonBlank() throws Exception {
        String toml = writer.toTomlString();
        assertThat(toml).isNotBlank();
    }

    @Test
    void get_teeMode_returnsDefault() {
        String mode = writer.get("tee.mode");
        assertThat(mode).isEqualToIgnoringCase("omissions");
    }

    @Test
    void get_teeEnabled_returnsTrue() {
        assertThat(writer.get("tee.enabled")).isEqualTo("true");
    }

    @Test
    void get_excludeCommands_returnsEmpty() {
        assertThat(writer.get("hooks.exclude_commands")).isEmpty();
    }

    @Test
    void get_unknownKey_throws() {
        assertThatThrownBy(() -> writer.get("nonexistent.key"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown config key");
    }

    @Test
    void set_teeMode_persistsAndIsReadBack() throws Exception {
        writer.set("tee.mode", "always");
        String value = writer.get("tee.mode");
        assertThat(value).isEqualTo("always");

        // Reset to avoid polluting other tests
        writer.set("tee.mode", "omissions");
    }

    @Test
    void set_excludeCommands_parsesCommaSeparated() throws Exception {
        writer.set("hooks.exclude_commands", "curl,playwright,wget");
        String value = writer.get("hooks.exclude_commands");
        assertThat(value).contains("curl");
        assertThat(value).contains("playwright");

        // Reset
        writer.set("hooks.exclude_commands", "");
    }

    @Test
    void reset_restoresDefaults() throws Exception {
        writer.set("tee.mode", "never");
        writer.reset();
        assertThat(writer.get("tee.mode")).isEqualTo("omissions");
    }

    @Test
    void set_unknownKey_throws() {
        assertThatThrownBy(() -> writer.set("nonexistent.key", "value"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
