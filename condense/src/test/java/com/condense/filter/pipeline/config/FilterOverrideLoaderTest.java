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
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """;
        Files.writeString(projectCondenseDir.resolve("filters.toml"), projectToml);

        Path configDir = tempDir.resolve("global-config");
        Files.createDirectories(configDir);
        String globalToml = """
            schema_version = 1
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
            schema_version = 1
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
            schema_version = 1
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
            schema_version = 1
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
            schema_version = 1
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

    @Test
    @DisplayName("Caching: Repeated lookups avoid redundant filesystem I/O")
    void testCacheAvoidsRedundantFileSystemWorkOnRepeatedInvocations() throws IOException {
        Path projectDir = tempDir.resolve("cached-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String toml = """
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """;
        Path overrideFile = condenseDir.resolve("filters.toml");
        Files.writeString(overrideFile, toml);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterPipeline defaultPipeline = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));

        // First call: reads from disk, caches pipeline
        FilterPipeline first = loader.resolvePipeline("npm install", defaultPipeline, projectDir);
        assertThat(first).isNotSameAs(defaultPipeline);
        assertThat(first.execute("\u001B[31merror\u001B[0m")).isEqualTo("error");

        // Physically delete file from disk to prove subsequent call does not touch disk
        Files.delete(overrideFile);
        assertThat(Files.exists(overrideFile)).isFalse();

        // Second call: served from in-memory cache without hitting disk
        FilterPipeline second = loader.resolvePipeline("npm install", defaultPipeline, projectDir);
        assertThat(second).isSameAs(first);
        assertThat(second.execute("\u001B[31merror\u001B[0m")).isEqualTo("error");

        // Negative caching test: non-existent override cached
        Path emptyProject = tempDir.resolve("empty-cached-project");
        Files.createDirectories(emptyProject);

        FilterPipeline noOverrideFirst = loader.resolvePipeline("ls", defaultPipeline, emptyProject);
        assertThat(noOverrideFirst).isSameAs(defaultPipeline);

        // Now create a file on disk; because negative existence is cached, second call still returns default without I/O
        Files.createDirectories(emptyProject.resolve(".condense"));
        Files.writeString(emptyProject.resolve(".condense/filters.toml"), toml);

        FilterPipeline noOverrideSecond = loader.resolvePipeline("ls", defaultPipeline, emptyProject);
        assertThat(noOverrideSecond).isSameAs(defaultPipeline);
    }

    @Test
    @DisplayName("Caching: Cache invalidation reloads modified configuration from disk")
    void testCacheInvalidationReloadsModifiedFiles() throws IOException {
        Path projectDir = tempDir.resolve("invalidation-project");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String tomlV1 = """
            schema_version = 1
            [filters."cmd"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """;
        Path overrideFile = condenseDir.resolve("filters.toml");
        Files.writeString(overrideFile, tomlV1);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterPipeline defaultPipeline = FilterPipeline.of();

        // Initial load: Version 1 (ansi_strip)
        FilterPipeline v1 = loader.resolvePipeline("cmd", defaultPipeline, projectDir);
        assertThat(v1.execute("\u001B[32mhello\u001B[0m")).isEqualTo("hello");

        // Update file on disk to Version 2 (deduplication)
        String tomlV2 = """
            schema_version = 1
            [filters."cmd"]
            stages = [
              { strategy = "deduplication", window_size = 5 }
            ]
            """;
        Files.writeString(overrideFile, tomlV2);

        // Before invalidation: cache serves Version 1
        FilterPipeline stillV1 = loader.resolvePipeline("cmd", defaultPipeline, projectDir);
        assertThat(stillV1).isSameAs(v1);

        // Invalidate cache for project
        loader.invalidateCache(projectDir);

        // After invalidation: reloads Version 2 from disk
        FilterPipeline v2 = loader.resolvePipeline("cmd", defaultPipeline, projectDir);
        assertThat(v2).isNotSameAs(v1);
        assertThat(v2.execute("repeat\nrepeat\nother")).isEqualTo("repeat (×2)\nother");
    }

    @Test
    @DisplayName("Caching: Different project directories maintain isolated cache entries")
    void testMultiDirectoryIsolation() throws IOException {
        Path projectA = tempDir.resolve("project-a");
        Path projectB = tempDir.resolve("project-b");
        Files.createDirectories(projectA.resolve(".condense"));
        Files.createDirectories(projectB.resolve(".condense"));

        String tomlA = """
            schema_version = 1
            [filters."shared-cmd"]
            stages = [ { strategy = "ansi_strip" } ]
            """;
        String tomlB = """
            schema_version = 1
            [filters."shared-cmd"]
            stages = [ { strategy = "tree_compression" } ]
            """;

        Files.writeString(projectA.resolve(".condense/filters.toml"), tomlA);
        Files.writeString(projectB.resolve(".condense/filters.toml"), tomlB);

        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterPipeline defaultPipeline = FilterPipeline.of();

        FilterPipeline pipelineA = loader.resolvePipeline("shared-cmd", defaultPipeline, projectA);
        FilterPipeline pipelineB = loader.resolvePipeline("shared-cmd", defaultPipeline, projectB);

        assertThat(pipelineA).isNotSameAs(pipelineB);
        assertThat(pipelineA.execute("\u001B[32mtext\u001B[0m")).isEqualTo("text");
        assertThat(pipelineB.execute("dir/file.txt\ndir/file2.txt")).contains("dir/");
    }

    @Test
    @DisplayName("Prefix match applies override for npm install --verbose")
    void testPrefixCommandMatching() throws IOException {
        Path projectDir = tempDir.resolve("prefix-project");
        Files.createDirectories(projectDir.resolve(".condense"));
        Files.writeString(projectDir.resolve(".condense/filters.toml"), """
            schema_version = 1
            [filters."npm install"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterPipeline fallback = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));
        FilterPipeline resolved = loader.resolvePipeline("npm install --verbose", fallback, projectDir);
        assertThat(resolved.execute("\u001B[31mred\u001B[0m")).isEqualTo("red");
    }

    @Test
    @DisplayName("Empty stages replace the default pipeline with identity")
    void testEmptyStagesReplaceDefault() throws IOException {
        Path projectDir = tempDir.resolve("empty-stages");
        Files.createDirectories(projectDir.resolve(".condense"));
        Files.writeString(projectDir.resolve(".condense/filters.toml"), """
            schema_version = 1
            [filters."ls"]
            stages = []
            """);
        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterPipeline fallback = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));
        FilterPipeline resolved = loader.resolvePipeline("ls", fallback, projectDir);
        assertThat(resolved).isNotSameAs(fallback);
        assertThat(resolved.execute("keep me")).isEqualTo("keep me");
    }

    @Test
    @DisplayName("json_structure override is constructible")
    void testJsonStructureOverride() throws IOException {
        Path projectDir = tempDir.resolve("json-project");
        Files.createDirectories(projectDir.resolve(".condense"));
        Files.writeString(projectDir.resolve(".condense/filters.toml"), """
            schema_version = 1
            [filters."aws"]
            stages = [ { strategy = "json_structure" } ]
            """);
        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterPipeline resolved = loader.resolvePipeline("aws", FilterPipeline.of(), projectDir);
        assertThat(resolved.execute("not-json")).isEqualTo("not-json");
    }

    @Test
    @DisplayName("Global override applies when project file exists but command is unmatched")
    void testGlobalWhenProjectFileMissesCommand() throws IOException {
        Path projectDir = tempDir.resolve("partial-project");
        Files.createDirectories(projectDir.resolve(".condense"));
        Files.writeString(projectDir.resolve(".condense/filters.toml"), """
            schema_version = 1
            [filters."ls"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        Path configDir = tempDir.resolve("global-partial");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("filters.toml"), """
            schema_version = 1
            [filters."npm install"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        PlatformDirs platformDirs = new PlatformDirs() {
            @Override
            public Path resolveConfigDir() {
                return configDir;
            }
        };
        FilterOverrideLoader loader = new FilterOverrideLoader(platformDirs);
        FilterPipeline fallback = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));
        FilterPipeline resolved = loader.resolvePipeline("npm install", fallback, projectDir);
        assertThat(resolved.execute("\u001B[32mhi\u001B[0m")).isEqualTo("hi");
    }

    @Test
    @DisplayName("Concurrent resolve does not throw")
    void testConcurrentResolve() throws Exception {
        Path projectDir = tempDir.resolve("concurrent-project");
        Files.createDirectories(projectDir.resolve(".condense"));
        Files.writeString(projectDir.resolve(".condense/filters.toml"), """
            schema_version = 1
            [filters."ls"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        FilterOverrideLoader loader = new FilterOverrideLoader(new PlatformDirs());
        FilterPipeline fallback = FilterPipeline.of();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 32; i++) {
                futures.add(pool.submit(() -> loader.resolvePipeline("ls", fallback, projectDir).execute("x")));
            }
            for (var future : futures) {
                assertThat(future.get()).isEqualTo("x");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
