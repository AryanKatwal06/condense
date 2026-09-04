package com.condense.filter.pipeline.config;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.fs.LsFilter;
import com.condense.filter.node.ESLintFilter;
import com.condense.filter.node.NpmInstallFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FilterOverrideIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("NpmInstallFilter: default output matches built-in behavior when no override is present")
    void testNpmInstallFilterDefaultBehavior() {
        NpmInstallFilter filter = new NpmInstallFilter();
        ExecutionResult result = new ExecutionResult(
            0,
            "added 42 packages in 3s\nfound 0 vulnerabilities\n",
            "",
            150L
        );

        FilterResult filterResult = filter.apply("npm install", result, CondenseConfig.defaults(), 0, false);
        assertThat(filterResult.output()).isEqualTo("✓ npm install: 42 packages | found 0 vulnerabilit");
    }

    @Test
    @DisplayName("NpmInstallFilter: project-local override customizes filtering pipeline end-to-end")
    void testNpmInstallFilterProjectOverride() throws IOException {
        Path projectDir = tempDir.resolve("npm-custom-proj");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String customToml = """
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" },
              { strategy = "deduplication", window_size = 10 }
            ]
            """;
        Files.writeString(condenseDir.resolve("filters.toml"), customToml);

        FilterOverrideLoader loader = new FilterOverrideLoader();
        NpmInstallFilter filter = new NpmInstallFilter(new FilterOverrideLoader() {
            @Override
            public com.condense.filter.pipeline.FilterPipeline resolvePipeline(
                String command, com.condense.filter.pipeline.FilterPipeline defaultPipeline) {
                return loader.resolvePipeline(command, defaultPipeline, projectDir);
            }
        });

        String rawOutput = "\u001B[33mwarning\u001B[0m: deprecated pkg\nwarning: deprecated pkg\nwarning: deprecated pkg\n";
        ExecutionResult result = new ExecutionResult(0, rawOutput, "", 200L);

        FilterResult filterResult = filter.apply("npm install", result, CondenseConfig.defaults(), 0, false);
        assertThat(filterResult.output()).isEqualTo("warning: deprecated pkg (×3)");
    }

    @Test
    @DisplayName("LsFilter: default output matches built-in behavior when no override is present")
    void testLsFilterDefaultBehavior() {
        LsFilter filter = new LsFilter();
        String fileList = String.join("\n", java.util.stream.IntStream.range(1, 20)
            .mapToObj(i -> "src/file" + i + ".txt")
            .toList());

        ExecutionResult result = new ExecutionResult(0, fileList, "", 50L);
        FilterResult filterResult = filter.apply("ls", result, CondenseConfig.defaults(), 0, false);

        assertThat(filterResult.output()).contains("src/");
        assertThat(filterResult.output()).contains("(19 files)");
    }

    @Test
    @DisplayName("LsFilter: project override replaces tree compression with deduplication")
    void testLsFilterProjectOverride() throws IOException {
        Path projectDir = tempDir.resolve("ls-custom-proj");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String customToml = """
            schema_version = 1
            [filters."ls"]
            stages = [
              { strategy = "deduplication", window_size = 5 }
            ]
            """;
        Files.writeString(condenseDir.resolve("filters.toml"), customToml);

        FilterOverrideLoader loader = new FilterOverrideLoader();
        LsFilter filter = new LsFilter(new FilterOverrideLoader() {
            @Override
            public com.condense.filter.pipeline.FilterPipeline resolvePipeline(
                String command, com.condense.filter.pipeline.FilterPipeline defaultPipeline) {
                return loader.resolvePipeline(command, defaultPipeline, projectDir);
            }
        });

        String fileList = String.join("\n", java.util.stream.IntStream.range(1, 15)
            .mapToObj(i -> "duplicate_entry.txt")
            .toList());

        ExecutionResult result = new ExecutionResult(0, fileList, "", 50L);
        FilterResult filterResult = filter.apply("ls", result, CondenseConfig.defaults(), 0, false);

        assertThat(filterResult.output()).isEqualTo("duplicate_entry.txt (×14)");
    }

    @Test
    @DisplayName("ESLintFilter: default output matches built-in behavior when no override is present")
    void testESLintFilterDefaultBehavior() {
        ESLintFilter filter = new ESLintFilter();
        String rawOutput = """
            /path/to/file.js
               1:5  error    Unexpected var  no-var
               2:8  warning  Missing semi    semi
            """;

        ExecutionResult result = new ExecutionResult(1, rawOutput, "", 100L);
        FilterResult filterResult = filter.apply("eslint", result, CondenseConfig.defaults(), 0, false);

        assertThat(filterResult.output()).contains("eslint: 1 error(s), 1 warning(s)");
        assertThat(filterResult.output()).contains("no-var : 1");
        assertThat(filterResult.output()).contains("semi   : 1");
    }

    @Test
    @DisplayName("ESLintFilter: project override customizes grouping pattern")
    void testESLintFilterProjectOverride() throws IOException {
        Path projectDir = tempDir.resolve("eslint-custom-proj");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);

        String customToml = """
            schema_version = 1
            [filters."eslint"]
            stages = [
              { strategy = "grouping", pattern = "error\\\\s+(.+)$", include_other = false }
            ]
            """;
        Files.writeString(condenseDir.resolve("filters.toml"), customToml);

        FilterOverrideLoader loader = new FilterOverrideLoader();
        ESLintFilter filter = new ESLintFilter(new FilterOverrideLoader() {
            @Override
            public com.condense.filter.pipeline.FilterPipeline resolvePipeline(
                String command, com.condense.filter.pipeline.FilterPipeline defaultPipeline) {
                return loader.resolvePipeline(command, defaultPipeline, projectDir);
            }
        });

        String rawOutput = "1:1 error SyntaxError: unexpected token\n2:1 error SyntaxError: unexpected token\n";
        ExecutionResult result = new ExecutionResult(1, rawOutput, "", 100L);
        FilterResult filterResult = filter.apply("eslint", result, CondenseConfig.defaults(), 0, false);

        assertThat(filterResult.output()).contains("SyntaxError: unexpected token : 2");
    }
}
