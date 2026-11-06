package com.condense.core;

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
    void returnsDefaultsWhenNoConfigFileExists() throws IOException {
        // Use an isolated PlatformDirs so a developer's real config cannot leak in.
        Path missing = Files.createTempDirectory("condense-no-config")
            .resolve("config.toml");
        ConfigLoader isolated = new ConfigLoader(new PlatformDirs() {
            @Override
            public Path getConfigFile() {
                return missing;
            }
        });

        var config = isolated.load();
        assertThat(config).isNotNull();
        assertThat(config.tee()).isNotNull();
        assertThat(config.tee().enabled()).isTrue();
        assertThat(config.tee().mode()).isEqualTo(TeeMode.OMISSIONS);
        assertThat(config.hooks()).isNotNull();
        assertThat(config.hooks().excludeCommands()).isNotNull();
    }

    @Test
    void injectedLoaderReturnsUsableConfig() {
        var config = loader.load();
        assertThat(config).isNotNull();
        assertThat(config.tee()).isNotNull();
        assertThat(config.hooks()).isNotNull();
    }

    @Test
    void defaultTeeConfigHasCorrectValues() {
        var tee = CondenseConfig.defaults().tee();
        assertThat(tee.enabled()).isTrue();
        assertThat(tee.mode()).isEqualTo(TeeMode.OMISSIONS);
    }

    @Test
    void omissionRecoveryIsTheDefaultTeeMode() {
        assertThat(CondenseConfig.defaults().tee().mode()).isEqualTo(TeeMode.OMISSIONS);
        assertThat(new CondenseConfig.TeeConfig().mode()).isEqualTo(TeeMode.OMISSIONS);
        assertThat(new CondenseConfig.TeeConfig(true, null).mode()).isEqualTo(TeeMode.OMISSIONS);
    }

    @Test
    void defaultHooksConfigHasEmptyExcludeList() {
        var hooks = CondenseConfig.defaults().hooks();
        assertThat(hooks.excludeCommands()).isEmpty();
    }

    @Test
    void parsesValidTomlFromString() throws IOException {
        // Write a temp config and re-load
        Path tmp = Files.createTempFile("condense-test-config", ".toml");
        Files.writeString(tmp, """
            [hooks]
            exclude_commands = ["curl", "playwright"]

            [tee]
            enabled = false
            mode = "always"
            [commands.pytest]
            max_failures = 10

            [commands.cargo-test]
            show_timing = false
            """);

        try {
            // Directly parse using Jackson TOML mapper — isolated from CDI
            var mapper = new com.fasterxml.jackson.dataformat.toml.TomlMapper();
            CondenseConfig config = mapper.readValue(tmp.toFile(), CondenseConfig.class);

            assertThat(config.hooks().excludeCommands())
                .containsExactly("curl", "playwright");
            assertThat(config.tee().enabled()).isFalse();
            assertThat(config.tee().mode()).isEqualTo(TeeMode.ALWAYS);

            assertThat(config.commandConfig("pytest").maxFailures(20)).isEqualTo(10);
            assertThat(config.commandConfig("cargo-test").showTiming(true)).isFalse();
            assertThat(config.commandConfig("jest").maxFailures(20)).isEqualTo(20);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
