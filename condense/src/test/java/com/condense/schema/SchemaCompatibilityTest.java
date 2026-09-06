package com.condense.schema;

import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.ir.Document;
import com.condense.ir.JsonRenderer;
import com.condense.mcp.McpMessages;
import com.condense.persist.SchemaMigrator;
import com.condense.trust.TrustTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaCompatibilityTest {

    @TempDir
    Path tempDir;

    @Test
    void oldFilterConfigBuildsPipeline() throws Exception {
        Path file = copy("filter-v1-valid.toml");
        var parsed = FilterOverrideLoader.standalone().parseAndValidateFile(file, file.getParent());
        assertThat(parsed.validationResult().isValid()).isTrue();
        assertThat(parsed.fileConfig().filters()).isNotEmpty();
    }

    @Test
    void schemaAheadAndUnknownKeyFailOpenToDefault() throws Exception {
        FilterPipeline fallback = FilterPipeline.of((input, ctx) -> StageResult.continueWith("FALLBACK"));
        assertThat(resolve(copyToProject("filter-schema-ahead.toml"), fallback)).isSameAs(fallback);
        assertThat(resolve(copyToProject("filter-unknown-key.toml"), fallback)).isSameAs(fallback);
    }

    @Test
    void irWithoutTerminationStillParses() throws Exception {
        Document parsed = JsonRenderer.parse(resource("ir-v1-no-termination.json"));
        assertThat(parsed.schemaVersion()).isEqualTo(Document.SCHEMA_VERSION);
        assertThat(parsed.termination()).isNull();
        assertThat(parsed.childExitCode()).isZero();
    }

    @Test
    void irSchemaAheadIsRejected() {
        assertThatThrownBy(() -> JsonRenderer.parse(resource("ir-schema-ahead.json")))
            .isInstanceOfAny(IllegalArgumentException.class, UncheckedIOException.class);
    }

    @Test
    void unknownMcpProtocolFallsBack() {
        assertThat(McpMessages.negotiateProtocol("not-a-protocol"))
            .isEqualTo(McpMessages.FALLBACK_PROTOCOL);
        assertThat(McpMessages.FALLBACK_PROTOCOL).isEqualTo("2024-11-05");
    }

    @Test
    void sqliteTargetStaysTwo() {
        assertThat(SchemaMigrator.TARGET_VERSION).isEqualTo(2);
    }

    private FilterPipeline resolve(Path projectDir, FilterPipeline fallback) throws Exception {
        FilterOverrideLoader loader = TrustTestSupport.trustedLoader(tempDir.resolve("cfg"), projectDir);
        return loader.resolvePipeline("npm install", fallback, projectDir);
    }

    private Path copyToProject(String name) throws Exception {
        Path project = tempDir.resolve(name.replace(".toml", ""));
        Files.createDirectories(project.resolve(".condense"));
        Files.writeString(project.resolve(".condense/filters.toml"), resource(name));
        return project;
    }

    private Path copy(String name) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, resource(name));
        return file;
    }

    private static String resource(String name) throws Exception {
        try (var in = SchemaCompatibilityTest.class.getResourceAsStream("/schema/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
