package com.condense.filter.pipeline.config;

import com.condense.core.PlatformDirs;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.StageResult;
import com.condense.trust.TrustGate;
import com.condense.trust.TrustTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OverrideCacheFreshnessTest {

    @TempDir
    Path tempDir;

    @Test
    void mtimeAndSizeChangeReloadsPipeline() throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);
        Path file = condenseDir.resolve("filters.toml");
        Files.writeString(file, """
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """);
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        PlatformDirs dirs = TrustTestSupport.dirs(configDir);
        TrustTestSupport.trustProject(dirs, projectDir);
        FilterOverrideLoader loader = new FilterOverrideLoader(dirs);
        FilterPipeline def = FilterPipeline.of((input, ctx) -> StageResult.continueWith("DEFAULT"));

        FilterPipeline first = loader.resolvePipeline("npm install", def, projectDir);
        assertThat(first.execute("\u001B[32mhello\u001B[0m")).isEqualTo("hello");

        Files.writeString(file, """
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "deduplication", window_size = 10 }
            ]
            """);
        TrustTestSupport.trustProject(dirs, projectDir);
        FilterPipeline second = loader.resolvePipeline("npm install", def, projectDir);
        assertThat(second).isNotSameAs(first);
        assertThat(second.execute("a\na\na")).isNotEqualTo("\u001B[32mhello\u001B[0m");
        assertThat(second.execute("a\na\na")).doesNotContain("\u001B[");
    }

    @Test
    void absentFileAppearingIsLoaded() throws Exception {
        Path projectDir = tempDir.resolve("appear");
        Files.createDirectories(projectDir);
        Path configDir = tempDir.resolve("config-appear");
        Files.createDirectories(configDir);
        PlatformDirs dirs = TrustTestSupport.dirs(configDir);
        FilterOverrideLoader loader = new FilterOverrideLoader(dirs);
        FilterPipeline def = FilterPipeline.of((input, ctx) -> StageResult.continueWith("DEFAULT"));

        assertThat(loader.resolvePipeline("npm install", def, projectDir)).isSameAs(def);

        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);
        Files.writeString(condenseDir.resolve("filters.toml"), """
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """);
        TrustTestSupport.trustProject(dirs, projectDir);
        FilterPipeline loaded = loader.resolvePipeline("npm install", def, projectDir);
        assertThat(loaded).isNotSameAs(def);
        assertThat(loaded.execute("\u001B[32mx\u001B[0m")).isEqualTo("x");
    }

    @Test
    void hashMismatchReasonIsNotCollapsed() throws Exception {
        Path projectDir = tempDir.resolve("hash");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);
        Path file = condenseDir.resolve("filters.toml");
        Files.writeString(file, """
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """);
        Path configDir = tempDir.resolve("config-hash");
        Files.createDirectories(configDir);
        PlatformDirs dirs = TrustTestSupport.dirs(configDir);
        TrustTestSupport.trustProject(dirs, projectDir);
        FilterOverrideLoader loader = new FilterOverrideLoader(dirs);
        FilterPipeline def = FilterPipeline.of((input, ctx) -> StageResult.continueWith("DEFAULT"));
        loader.resolvePipeline("npm install", def, projectDir);

        Files.writeString(file, """
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            # mutated
            """);
        PipelineDecision decision = loader.resolveDecision("npm install", def, projectDir, null);
        assertThat(decision.pipeline()).isSameAs(def);
        assertThat(decision.skipped()).extracting(PipelineDecision.SkippedTier::reason)
            .contains("hash_mismatch");
    }

    @Test
    void matchingDefWithFailedBuildIsPipelineBuildFailed() throws Exception {
        Path projectDir = tempDir.resolve("build-fail");
        Path condenseDir = projectDir.resolve(".condense");
        Files.createDirectories(condenseDir);
        Files.writeString(condenseDir.resolve("filters.toml"), """
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """);
        Path configDir = tempDir.resolve("config-build");
        Files.createDirectories(configDir);
        PlatformDirs dirs = TrustTestSupport.dirs(configDir);
        TrustTestSupport.trustProject(dirs, projectDir);
        FilterOverrideLoader loader = new NullBuildLoader(dirs, new TrustGate(dirs));
        FilterPipeline def = FilterPipeline.of((input, ctx) -> StageResult.continueWith("DEFAULT"));
        PipelineDecision decision = loader.resolveDecision("npm install", def, projectDir, null);
        assertThat(decision.pipeline()).isSameAs(def);
        assertThat(decision.skipped()).extracting(PipelineDecision.SkippedTier::reason)
            .contains("pipeline_build_failed");
    }

    static final class NullBuildLoader extends FilterOverrideLoader {
        NullBuildLoader(PlatformDirs dirs, TrustGate gate) {
            super(dirs, gate);
        }

        @Override
        FilterPipeline buildPipelineFromDef(FilterOverrideConfig.FilterDef filterDef) {
            return null;
        }
    }
}
