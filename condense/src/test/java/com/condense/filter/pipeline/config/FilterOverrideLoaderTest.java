package com.condense.filter.pipeline.config;

import com.condense.core.PlatformDirs;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FilterOverrideLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Built-in default pipeline is returned when no override files exist")
    void testDefaultPipelineWhenNoOverrides() {
        PlatformDirs platformDirs = new PlatformDirs() {
            @Override
            public Path resolveConfigDir() {
                return tempDir.resolve("non_existent_config");
            }
        };

        FilterOverrideLoader loader = new FilterOverrideLoader(platformDirs);
        FilterPipeline defaultPipeline = FilterPipeline.of((input, ctx) -> StageResult.continueWith("DEFAULT: " + input));

        FilterPipeline resolved = loader.resolvePipeline("npm install", defaultPipeline, tempDir.resolve("empty_project"));
        assertThat(resolved).isSameAs(defaultPipeline);
        assertThat(resolved.execute("test")).isEqualTo("DEFAULT: test");
    }

    @Test
    @DisplayName("Project-local override takes precedence over global override and default")
    void testProjectOverridePrecedence() throws IOException {
        Path projectDir = tempDir.resolve("my-project");
        Path projectCondenseDir = projectDir.resolve(".condense");
        Files.createDirectories(projectCondenseDir);

        String projectToml = """
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """;
        Files.writeString(projectCondenseDir.resolve("filters.toml"), projectToml);

        Path configDir = tempDir.resolve("global-config");
        Files.createDirectories(configDir);
        String globalToml = """
            [filters."npm install"]
            stages = [
              { strategy = "deduplication", window_size = 10 }
            ]
            """;
        Files.writeString(configDir.resolve("filters.toml"), globalToml);

        PlatformDirs platformDirs = new PlatformDirs() {
            @Override
            public Path resolveConfigDir() {
                return configDir;
            }
        };

        FilterOverrideLoader loader = new FilterOverrideLoader(platformDirs);
        FilterPipeline defaultPipeline = FilterPipeline.of((input, ctx) -> StageResult.continueWith("DEFAULT: " + input));

        FilterPipeline resolved = loader.resolvePipeline("npm install", defaultPipeline, projectDir);
        assertThat(resolved).isNotSameAs(defaultPipeline);

        // Project override has ansi_strip
        String input = "\u001B[32mhello\u001B[0m";
        assertThat(resolved.execute(input)).isEqualTo("hello");
    }

    @Test
    @DisplayName("User-global override takes precedence when project override is absent")
    void testGlobalOverridePrecedence() throws IOException {
        Path projectDir = tempDir.resolve("clean-project");
        Files.createDirectories(projectDir);

        Path configDir = tempDir.resolve("user-config");
        Files.createDirectories(configDir);
        String globalToml = """
            [filters."ls"]
            stages = [
              { strategy = "tree_compression" }
            ]
            """;
        Files.writeString(configDir.resolve("filters.toml"), globalToml);

        PlatformDirs platformDirs = new PlatformDirs() {
            @Override
            public Path resolveConfigDir() {
                return configDir;
            }
        };

        FilterOverrideLoader loader = new FilterOverrideLoader(platformDirs);
        FilterPipeline defaultPipeline = FilterPipeline.of((input, ctx) -> StageResult.continueWith("DEFAULT"));

        FilterPipeline resolved = loader.resolvePipeline("ls", defaultPipeline, projectDir);
        assertThat(resolved).isNotSameAs(defaultPipeline);

        String fileList = "src/main/App.java\nsrc/main/Util.java\nsrc/test/AppTest.java";
        String output = resolved.execute(fileList);
        assertThat(output).contains("src/").contains("main/").contains("test/");
    }

    @Test
    @DisplayName("Deduplication strategy parameters are correctly parsed and applied")
    void testDeduplicationStrategyParameters() throws IOException {
        Path projectDir = tempDir.resolve("dedup-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String toml = """
            [filters."custom-cmd"]
            stages = [
              { strategy = "deduplication", window_size = 5 }
            ]
            """;
        Files.writeString(condenseDir.resolve("filters.toml"), toml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterPipeline defaultPipeline = FilterPipeline.of();

        FilterPipeline resolved = loader.resolvePipeline("custom-cmd", defaultPipeline, projectDir);
        String input = "line1\nline1\nline1\nline2";
        assertThat(resolved.execute(input)).isEqualTo("line1 (×3)\nline2");
    }

    @Test
    @DisplayName("Grouping strategy parameters are correctly parsed and applied")
    void testGroupingStrategyParameters() throws IOException {
        Path projectDir = tempDir.resolve("group-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String toml = """
            [filters."lint"]
            stages = [
              { strategy = "grouping", pattern = "rule: (\\\\S+)", include_other = true }
            ]
            """;
        Files.writeString(condenseDir.resolve("filters.toml"), toml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterPipeline resolved = loader.resolvePipeline("lint", FilterPipeline.of(), projectDir);

        String input = "rule: no-unused-vars\nrule: no-unused-vars\nrule: eqeqeq\nother error";
        String result = resolved.execute(input);
        assertThat(result).contains("no-unused-vars : 2");
        assertThat(result).contains("eqeqeq         : 1");
        assertThat(result).contains("(other)        : 1");
    }

    @Test
    @DisplayName("StateMachine strategy transitions and default actions are parsed and applied")
    void testStateMachineStrategyConfiguration() throws IOException {
        Path projectDir = tempDir.resolve("sm-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String toml = """
            [filters."build-log"]
            stages = [
              { strategy = "state_machine", initial_state = "IDLE", transitions = [{ from_state = "IDLE", pattern = "^START", action = "DISCARD", next_state = "CAPTURING" }, { from_state = "CAPTURING", pattern = "^ERROR:", action = "EMIT", next_state = "CAPTURING" }, { from_state = "CAPTURING", pattern = "^STOP", action = "DISCARD", next_state = "IDLE" }], default_actions = { CAPTURING = "DISCARD", IDLE = "DISCARD" } }
            ]
            """;
        Files.writeString(condenseDir.resolve("filters.toml"), toml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterPipeline resolved = loader.resolvePipeline("build-log", FilterPipeline.of(), projectDir);

        String input = "INFO: init\nSTART\nINFO: compiling\nERROR: null pointer\nERROR: timeout\nSTOP\nINFO: done";
        String result = resolved.execute(input);
        assertThat(result).isEqualTo("ERROR: null pointer\nERROR: timeout");
    }

    @Test
    @DisplayName("Fail-open: Malformed TOML safely falls back to default pipeline")
    void testFailOpenOnMalformedToml() throws IOException {
        Path projectDir = tempDir.resolve("broken-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        Files.writeString(condenseDir.resolve("filters.toml"), "INVALID [[ TOML == syntax :::");

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterPipeline defaultPipeline = FilterPipeline.of((input, ctx) -> StageResult.continueWith("SAFE_FALLBACK"));

        FilterPipeline resolved = loader.resolvePipeline("npm install", defaultPipeline, projectDir);
        assertThat(resolved).isSameAs(defaultPipeline);
        assertThat(resolved.execute("any input")).isEqualTo("SAFE_FALLBACK");
    }
}
