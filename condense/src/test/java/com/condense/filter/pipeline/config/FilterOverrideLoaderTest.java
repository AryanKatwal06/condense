package com.condense.filter.pipeline.config;

import com.condense.core.PlatformDirs;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.StageResult;
import com.condense.trust.Capability;
import com.condense.trust.TrustGate;
import com.condense.trust.TrustTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class FilterOverrideLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Built-in default pipeline is returned when no override files exist")
    void testDefaultPipelineWhenNoOverrides() throws IOException {
        FilterOverrideLoader loader = isolatedLoader("no-overrides");
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

        PlatformDirs platformDirs = TrustTestSupport.dirs(configDir);
        TrustTestSupport.trustProject(platformDirs, projectDir);
        FilterOverrideLoader loader = new FilterOverrideLoader(platformDirs);
        FilterPipeline defaultPipeline = FilterPipeline.of((input, ctx) -> StageResult.continueWith("DEFAULT: " + input));

        FilterPipeline resolved = loader.resolvePipeline("npm install", defaultPipeline, projectDir);
        assertThat(resolved).isNotSameAs(defaultPipeline);

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

        FilterOverrideLoader loader = new FilterOverrideLoader(TrustTestSupport.dirs(configDir));
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
        Path projectDir = writeProject("dedup-project", """
            schema_version = 1
            [filters."custom-cmd"]
            stages = [
              { strategy = "deduplication", window_size = 5 }
            ]
            """);

        FilterOverrideLoader loader = trustedLoader("dedup-cfg", projectDir);
        FilterPipeline resolved = loader.resolvePipeline("custom-cmd", FilterPipeline.of(), projectDir);
        String input = "line1\nline1\nline1\nline2";
        assertThat(resolved.execute(input)).isEqualTo("line1 (×3)\nline2");
    }

    @Test
    @DisplayName("Grouping strategy parameters are correctly parsed and applied")
    void testGroupingStrategyParameters() throws IOException {
        Path projectDir = writeProject("group-project", """
            schema_version = 1
            [filters."lint"]
            stages = [
              { strategy = "grouping", pattern = "rule: (\\\\S+)", include_other = true }
            ]
            """);

        FilterOverrideLoader loader = trustedLoader("group-cfg", projectDir);
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
        Path projectDir = writeProject("sm-project", """
            schema_version = 1
            [filters."build-log"]
            stages = [
              { strategy = "state_machine", initial_state = "IDLE", transitions = [{ from_state = "IDLE", pattern = "^START", action = "DISCARD", next_state = "CAPTURING" }, { from_state = "CAPTURING", pattern = "^ERROR:", action = "EMIT", next_state = "CAPTURING" }, { from_state = "CAPTURING", pattern = "^STOP", action = "DISCARD", next_state = "IDLE" }], default_actions = { CAPTURING = "DISCARD", IDLE = "DISCARD" } }
            ]
            """);

        FilterOverrideLoader loader = trustedLoader("sm-cfg", projectDir);
        FilterPipeline resolved = loader.resolvePipeline("build-log", FilterPipeline.of(), projectDir);

        String input = "INFO: init\nSTART\nINFO: compiling\nERROR: null pointer\nERROR: timeout\nSTOP\nINFO: done";
        String result = resolved.execute(input);
        assertThat(result).isEqualTo("ERROR: null pointer\nERROR: timeout");
    }

    @Test
    @DisplayName("Fail-open: Malformed TOML safely falls back to default pipeline")
    void testFailOpenOnMalformedToml() throws IOException {
        Path projectDir = writeProject("broken-project", "INVALID [[ TOML == syntax :::");

        FilterOverrideLoader loader = isolatedLoader("broken-cfg");
        FilterPipeline defaultPipeline = FilterPipeline.of((input, ctx) -> StageResult.continueWith("SAFE_FALLBACK"));

        FilterPipeline resolved = loader.resolvePipeline("npm install", defaultPipeline, projectDir);
        assertThat(resolved).isSameAs(defaultPipeline);
        assertThat(resolved.execute("any input")).isEqualTo("SAFE_FALLBACK");
    }

    @Test
    @DisplayName("Caching: Repeated lookups avoid redundant filesystem I/O")
    void testCacheAvoidsRedundantFileSystemWorkOnRepeatedInvocations() throws IOException {
        Path projectDir = writeProject("cached-project", """
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """);
        Path overrideFile = projectDir.resolve(".condense/filters.toml");

        FilterOverrideLoader loader = trustedLoader("cached-cfg", projectDir);
        FilterPipeline defaultPipeline = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));

        FilterPipeline first = loader.resolvePipeline("npm install", defaultPipeline, projectDir);
        assertThat(first).isNotSameAs(defaultPipeline);
        assertThat(first.execute("\u001B[31merror\u001B[0m")).isEqualTo("error");

        Files.delete(overrideFile);
        assertThat(Files.exists(overrideFile)).isFalse();

        FilterPipeline second = loader.resolvePipeline("npm install", defaultPipeline, projectDir);
        assertThat(second).isSameAs(first);
        assertThat(second.execute("\u001B[31merror\u001B[0m")).isEqualTo("error");

        Path emptyProject = tempDir.resolve("empty-cached-project");
        Files.createDirectories(emptyProject);

        FilterPipeline noOverrideFirst = loader.resolvePipeline("ls", defaultPipeline, emptyProject);
        assertThat(noOverrideFirst).isSameAs(defaultPipeline);

        Files.createDirectories(emptyProject.resolve(".condense"));
        Files.writeString(emptyProject.resolve(".condense/filters.toml"), """
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """);

        FilterPipeline noOverrideSecond = loader.resolvePipeline("ls", defaultPipeline, emptyProject);
        assertThat(noOverrideSecond).isSameAs(defaultPipeline);
    }

    @Test
    @DisplayName("Caching: Cache invalidation reloads modified configuration from disk")
    void testCacheInvalidationReloadsModifiedFiles() throws IOException {
        Path projectDir = writeProject("invalidation-project", """
            schema_version = 1
            [filters."cmd"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """);
        Path overrideFile = projectDir.resolve(".condense/filters.toml");
        Path configDir = tempDir.resolve("inv-cfg");
        PlatformDirs dirs = TrustTestSupport.dirs(configDir);
        TrustTestSupport.trustProject(dirs, projectDir);
        FilterOverrideLoader loader = new FilterOverrideLoader(dirs);
        FilterPipeline defaultPipeline = FilterPipeline.of();

        FilterPipeline v1 = loader.resolvePipeline("cmd", defaultPipeline, projectDir);
        assertThat(v1.execute("\u001B[32mhello\u001B[0m")).isEqualTo("hello");

        Files.writeString(overrideFile, """
            schema_version = 1
            [filters."cmd"]
            stages = [
              { strategy = "deduplication", window_size = 5 }
            ]
            """);

        FilterPipeline stillV1 = loader.resolvePipeline("cmd", defaultPipeline, projectDir);
        assertThat(stillV1).isSameAs(v1);

        loader.invalidateCache(projectDir);
        TrustTestSupport.trustProject(dirs, projectDir);

        FilterPipeline v2 = loader.resolvePipeline("cmd", defaultPipeline, projectDir);
        assertThat(v2).isNotSameAs(v1);
        assertThat(v2.execute("repeat\nrepeat\nother")).isEqualTo("repeat (×2)\nother");
    }

    @Test
    @DisplayName("Caching: Different project directories maintain isolated cache entries")
    void testMultiDirectoryIsolation() throws IOException {
        Path projectA = writeProject("project-a", """
            schema_version = 1
            [filters."shared-cmd"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        Path projectB = writeProject("project-b", """
            schema_version = 1
            [filters."shared-cmd"]
            stages = [ { strategy = "tree_compression" } ]
            """);

        Path configDir = tempDir.resolve("multi-cfg");
        PlatformDirs dirs = TrustTestSupport.dirs(configDir);
        TrustTestSupport.trustProject(dirs, projectA);
        TrustTestSupport.trustProject(dirs, projectB);
        FilterOverrideLoader loader = new FilterOverrideLoader(dirs);
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
        Path projectDir = writeProject("prefix-project", """
            schema_version = 1
            [filters."npm install"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        FilterOverrideLoader loader = trustedLoader("prefix-cfg", projectDir);
        FilterPipeline fallback = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));
        FilterPipeline resolved = loader.resolvePipeline("npm install --verbose", fallback, projectDir);
        assertThat(resolved.execute("\u001B[31mred\u001B[0m")).isEqualTo("red");
    }

    @Test
    @DisplayName("Empty stages replace the default pipeline with identity")
    void testEmptyStagesReplaceDefault() throws IOException {
        Path projectDir = writeProject("empty-stages", """
            schema_version = 1
            [filters."ls"]
            stages = []
            """);
        FilterOverrideLoader loader = trustedLoader("empty-cfg", projectDir);
        FilterPipeline fallback = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));
        FilterPipeline resolved = loader.resolvePipeline("ls", fallback, projectDir);
        assertThat(resolved).isNotSameAs(fallback);
        assertThat(resolved.execute("keep me")).isEqualTo("keep me");
    }

    @Test
    @DisplayName("json_structure override is constructible")
    void testJsonStructureOverride() throws IOException {
        Path projectDir = writeProject("json-project", """
            schema_version = 1
            [filters."aws"]
            stages = [ { strategy = "json_structure" } ]
            """);
        FilterOverrideLoader loader = trustedLoader("json-cfg", projectDir);
        FilterPipeline resolved = loader.resolvePipeline("aws", FilterPipeline.of(), projectDir);
        assertThat(resolved.execute("not-json")).isEqualTo("not-json");
    }

    @Test
    @DisplayName("Global override applies when project file exists but command is unmatched")
    void testGlobalWhenProjectFileMissesCommand() throws IOException {
        Path projectDir = writeProject("partial-project", """
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
        PlatformDirs platformDirs = TrustTestSupport.dirs(configDir);
        TrustTestSupport.trustProject(platformDirs, projectDir);
        FilterOverrideLoader loader = new FilterOverrideLoader(platformDirs);
        FilterPipeline fallback = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));
        FilterPipeline resolved = loader.resolvePipeline("npm install", fallback, projectDir);
        assertThat(resolved.execute("\u001B[32mhi\u001B[0m")).isEqualTo("hi");
    }

    @Test
    @DisplayName("Concurrent resolve does not throw")
    void testConcurrentResolve() throws Exception {
        Path projectDir = writeProject("concurrent-project", """
            schema_version = 1
            [filters."ls"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        FilterOverrideLoader loader = trustedLoader("concurrent-cfg", projectDir);
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

    @Test
    @DisplayName("Untrusted project override is skipped and hints on stderr")
    void untrustedProjectOverrideIsSkipped() throws IOException {
        Path projectDir = writeProject("untrusted-project", """
            schema_version = 1
            [filters."npm install"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        FilterOverrideLoader loader = isolatedLoader("untrusted-cfg");
        FilterPipeline fallback = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));

        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err));
            FilterPipeline resolved = loader.resolvePipeline("npm install", fallback, projectDir);
            assertThat(resolved).isSameAs(fallback);
        } finally {
            System.setErr(originalErr);
        }
        assertThat(err.toString()).contains(TrustGate.SKIP_HINT);
    }

    @Test
    @DisplayName("Hash change skips a previously trusted project file")
    void hashChangeSkipsPreviouslyTrustedFile() throws IOException {
        Path projectDir = writeProject("hash-project", """
            schema_version = 1
            [filters."cmd"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        Path configDir = tempDir.resolve("hash-cfg");
        PlatformDirs dirs = TrustTestSupport.dirs(configDir);
        TrustTestSupport.trustProject(dirs, projectDir);
        FilterOverrideLoader loader = new FilterOverrideLoader(dirs);
        FilterPipeline fallback = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));

        assertThat(loader.resolvePipeline("cmd", fallback, projectDir).execute("\u001B[32mhi\u001B[0m"))
            .isEqualTo("hi");

        Files.writeString(projectDir.resolve(".condense/filters.toml"), """
            schema_version = 1
            [filters."cmd"]
            stages = [ { strategy = "deduplication", window_size = 5 } ]
            """);
        loader.invalidateCache(projectDir);

        FilterPipeline afterChange = loader.resolvePipeline("cmd", fallback, projectDir);
        assertThat(afterChange).isSameAs(fallback);
    }

    @Test
    @DisplayName("Reshape file with reduce-only grant is skipped")
    void reshapeFileWithReduceGrantIsSkipped() throws IOException {
        Path projectDir = writeProject("reshape-project", """
            schema_version = 1
            [filters."lint"]
            stages = [ { strategy = "grouping", pattern = "rule: (\\\\S+)" } ]
            """);
        Path configDir = tempDir.resolve("reshape-cfg");
        PlatformDirs dirs = TrustTestSupport.dirs(configDir);
        TrustTestSupport.trustProject(dirs, projectDir, EnumSet.of(Capability.REDUCE));
        FilterOverrideLoader loader = new FilterOverrideLoader(dirs);
        FilterPipeline fallback = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));

        assertThat(loader.resolvePipeline("lint", fallback, projectDir)).isSameAs(fallback);
    }

    @Test
    @DisplayName("Global override still applies when the project file is untrusted")
    void globalAppliesWhenProjectFileIsUntrusted() throws IOException {
        Path projectDir = writeProject("untrusted-vs-global", """
            schema_version = 1
            [filters."npm install"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        Path configDir = tempDir.resolve("global-untrusted");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("filters.toml"), """
            schema_version = 1
            [filters."npm install"]
            stages = [ { strategy = "deduplication", window_size = 5 } ]
            """);
        FilterOverrideLoader loader = new FilterOverrideLoader(TrustTestSupport.dirs(configDir));
        FilterPipeline fallback = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));

        FilterPipeline resolved = loader.resolvePipeline("npm install", fallback, projectDir);
        assertThat(resolved).isNotSameAs(fallback);
        assertThat(resolved.execute("warn\nwarn")).isEqualTo("warn (×2)");
    }

    private Path writeProject(String name, String toml) throws IOException {
        Path projectDir = tempDir.resolve(name);
        Files.createDirectories(projectDir.resolve(".condense"));
        Files.writeString(projectDir.resolve(".condense/filters.toml"), toml);
        return projectDir;
    }

    private FilterOverrideLoader isolatedLoader(String configName) throws IOException {
        return TrustTestSupport.isolatedLoader(tempDir.resolve(configName));
    }

    private FilterOverrideLoader trustedLoader(String configName, Path projectDir) throws IOException {
        return TrustTestSupport.trustedLoader(tempDir.resolve(configName), projectDir);
    }
}
