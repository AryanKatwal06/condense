package com.condense.schema;

import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.ir.Document;
import com.condense.ir.JsonRenderer;
import com.condense.ir.TextRenderer;
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
    void oldResourceDocumentWithoutFormatStillParses() throws Exception {
        Document parsed = JsonRenderer.parse(resource("ir-v1-resource-containers.json"));
        assertThat(parsed.kind()).isEqualTo(Document.DocumentKind.RESOURCE);
        Document.ResourceDocument payload = (Document.ResourceDocument) parsed.document();
        assertThat(payload.format()).isNull();
        assertThat(payload.infra()).isFalse();
        assertThat(payload.rows()).hasSize(1);
        assertThat(payload.rows().getFirst().id()).isEqualTo("a1b2c3d4");
        assertThat(TextRenderer.render(parsed)).contains("ID       IMAGE");
        assertThat(TextRenderer.render(parsed)).doesNotContain("Plan:");
    }

    @Test
    void infraResourceDocumentParsesAndRendersWithoutDockerHeader() throws Exception {
        Document parsed = JsonRenderer.parse(resource("ir-v1-resource-infra.json"));
        assertThat(parsed.kind()).isEqualTo(Document.DocumentKind.RESOURCE);
        Document.ResourceDocument payload = (Document.ResourceDocument) parsed.document();
        assertThat(payload.infra()).isTrue();
        assertThat(payload.add()).isEqualTo(1);
        assertThat(payload.rows().getFirst().address()).isEqualTo("aws_instance.web");
        String text = TextRenderer.render(parsed);
        assertThat(text).startsWith("Plan: 1 to add, 0 to change, 0 to destroy");
        assertThat(text).contains("aws_instance.web create");
        assertThat(text).doesNotContain("ID       IMAGE");
    }

    @Test
    void oldTestDocumentWithoutOptionalFieldsStillParses() throws Exception {
        Document parsed = JsonRenderer.parse(resource("ir-v1-test-pytest.json"));
        assertThat(parsed.kind()).isEqualTo(Document.DocumentKind.TEST);
        Document.TestDocument payload = (Document.TestDocument) parsed.document();
        assertThat(payload.tool()).isNull();
        assertThat(payload.skipped()).isNull();
        assertThat(payload.total()).isNull();
        assertThat(payload.cases()).hasSize(1);
        assertThat(payload.cases().getFirst().file()).isNull();
        assertThat(payload.cases().getFirst().stack()).isNull();
        assertThat(TextRenderer.render(parsed)).contains("FAILED tests/test_math.py::test_mul");
    }

    @Test
    void trxTestDocumentParsesOptionalFields() throws Exception {
        Document parsed = JsonRenderer.parse(resource("ir-v1-test-trx.json"));
        assertThat(parsed.kind()).isEqualTo(Document.DocumentKind.TEST);
        Document.TestDocument payload = (Document.TestDocument) parsed.document();
        assertThat(payload.tool()).isEqualTo("trx");
        assertThat(payload.total()).isEqualTo(39);
        assertThat(payload.cases().getFirst().file()).isEqualTo("InvoiceTests.cs");
        assertThat(payload.cases().getFirst().durationMs()).isEqualTo(12);
        assertThat(payload.cases().getFirst().stack()).contains("TestInvoiceTotal");
        assertThat(TextRenderer.render(parsed)).contains("Failed TestInvoiceTotal");
    }

    @Test
    void unknownMcpProtocolFallsBack() {
        assertThat(McpMessages.negotiateProtocol("not-a-protocol"))
            .isEqualTo(McpMessages.FALLBACK_PROTOCOL);
        assertThat(McpMessages.FALLBACK_PROTOCOL).isEqualTo("2024-11-05");
    }

    @Test
    void sqliteTargetIsThree() {
        assertThat(SchemaMigrator.TARGET_VERSION).isEqualTo(3);
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
