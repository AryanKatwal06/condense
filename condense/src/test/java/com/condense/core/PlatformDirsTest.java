package com.condense.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class PlatformDirsTest {

    @Inject
    PlatformDirs dirs;

    @Test
    void configDirIsCreatedAndIsADirectory() {
        var dir = dirs.getConfigDir();
        assertThat(dir).isNotNull();
        assertThat(Files.isDirectory(dir)).isTrue();
    }

    @Test
    void dataDirIsCreatedAndIsADirectory() {
        var dir = dirs.getDataDir();
        assertThat(dir).isNotNull();
        assertThat(Files.isDirectory(dir)).isTrue();
    }

    @Test
    void configFileHasCorrectFileName() {
        assertThat(dirs.getConfigFile().getFileName().toString()).isEqualTo("config.toml");
    }

    @Test
    void databaseFileHasCorrectFileName() {
        assertThat(dirs.getDatabaseFile().getFileName().toString()).isEqualTo("condense.db");
    }

    @Test
    void configFileIsInsideConfigDir() {
        assertThat(dirs.getConfigFile().toString())
            .startsWith(dirs.getConfigDir().toString());
    }

    @Test
    void databaseFileIsInsideDataDir() {
        assertThat(dirs.getDatabaseFile().toString())
            .startsWith(dirs.getDataDir().toString());
    }
}
