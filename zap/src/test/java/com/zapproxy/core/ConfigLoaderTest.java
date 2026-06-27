package com.zapproxy.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ConfigLoaderTest {

    @Inject
    ConfigLoader loader;

    @Test
    void returnsDefaultsWhenNoConfigFileExists() {
        // In the test environment no config file should exist yet.
        // Even if it does, defaults must not be null.
        var config = loader.load();
        assertThat(config).isNotNull();
        assertThat(config.tee()).isNotNull();
        assertThat(config.tee().enabled()).isTrue();
        assertThat(config.tee().mode()).isEqualTo(TeeMode.FAILURES);
        assertThat(config.hooks()).isNotNull();
        assertThat(config.hooks().excludeCommands()).isNotNull();
    }

    @Test
    void defaultTeeConfigHasCorrectValues() {
        var tee = ZapConfig.defaults().tee();
        assertThat(tee.enabled()).isTrue();
        assertThat(tee.mode()).isEqualTo(TeeMode.FAILURES);
    }

    @Test
    void defaultHooksConfigHasEmptyExcludeList() {
        var hooks = ZapConfig.defaults().hooks();
        assertThat(hooks.excludeCommands()).isEmpty();
    }

    @Test
    void parsesValidTomlFromString() throws IOException {
        // Write a temp config and re-load
        Path tmp = Files.createTempFile("zap-test-config", ".toml");
        Files.writeString(tmp, """
            [hooks]
            exclude_commands = ["curl", "playwright"]

            [tee]
            enabled = false
            mode = "always"
            """);

        try {
            // Directly parse using Jackson TOML mapper — isolated from CDI
            var mapper = new com.fasterxml.jackson.dataformat.toml.TomlMapper();
            ZapConfig config = mapper.readValue(tmp.toFile(), ZapConfig.class);

            assertThat(config.hooks().excludeCommands())
                .containsExactly("curl", "playwright");
            assertThat(config.tee().enabled()).isFalse();
            assertThat(config.tee().mode()).isEqualTo(TeeMode.ALWAYS);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
