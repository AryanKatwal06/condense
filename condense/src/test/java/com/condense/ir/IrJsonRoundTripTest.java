package com.condense.ir;

import com.condense.corpus.CorpusCatalog;
import com.condense.corpus.CorpusRunner;
import com.condense.core.FilterResult;
import com.condense.explain.ExplainReport;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterIncident;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IrJsonRoundTripTest {

    @Test
    void everyKindRoundTripsSchemaOneAndRejectsUnknownKeys() throws Exception {
        Document test = typed("pytest/typical", Document.DocumentKind.TEST);
        Document diagnostic = typed("eslint/typical", Document.DocumentKind.DIAGNOSTIC);
        Document dependency = typed("npm-install/typical", Document.DocumentKind.DEPENDENCY);
        Document resource = typed("docker-ps/typical", Document.DocumentKind.RESOURCE);
        Document opaque = typed("git-status/clean", Document.DocumentKind.OPAQUE);

        for (Document document : List.of(test, diagnostic, dependency, resource, opaque)) {
            String json = JsonRenderer.render(document);
            JsonNode tree = com.condense.core.Mappers.JSON.readTree(json);
            assertThat(tree.get("schema_version").asInt()).isEqualTo(Document.SCHEMA_VERSION);
            assertThat(tree.get("kind").asText()).isEqualTo(document.kind().wire());
            assertThat(tree.get("provenance").isObject()).isTrue();
            assertThat(tree.get("document").isObject()).isTrue();

            assertThat(tree.has("termination")).isFalse();

            Document parsed = JsonRenderer.parse(json);
            assertThat(parsed.kind()).isEqualTo(document.kind());
            assertThat(parsed.schemaVersion()).isEqualTo(1);
            assertThat(TextRenderer.render(parsed)).isEqualTo(TextRenderer.render(document));

            String withUnknown = json.substring(0, json.length() - 1) + ",\"unexpected_field\":true}";
            assertThatThrownBy(() -> JsonRenderer.parse(withUnknown))
                .isInstanceOf(UncheckedIOException.class);
        }
    }

    @Test
    void gitStatusTypicalIsOpaque() throws Exception {
        FilterResult applied = CorpusRunner.apply(entry("git-status/clean"));
        assertThat(applied.document().kind()).isEqualTo(Document.DocumentKind.OPAQUE);
        Document.OpaqueDocument payload = (Document.OpaqueDocument) applied.document().document();
        assertThat(payload.body()).isNotBlank();
        assertThat(payload.body()).doesNotStartWith("condense[filtered]");
    }

    @Test
    void irBuildFailureFallsOpenToOpaqueAndKeepsExit() {
        FilterContext context = FilterContext.of(
            "pytest", new ExecutionResult(1, "raw", "", 1L), CondenseConfig.defaults(), 0, false);
        context.documentBuilder().test(new Document.TestDocument(
            List.of(), 0, 1, 0, List.of("FAILED tests/test_math.py::test_mul"), ""));
        context.documentBuilder().failNextBuild(new IllegalStateException("forced ir failure"));
        Document document = Documents.fromContext(
            context, "pytest", "PytestFilter", context.result(), true, "fallback body");
        assertThat(document.kind()).isEqualTo(Document.DocumentKind.OPAQUE);
        assertThat(document.childExitCode()).isEqualTo(1);
        assertThat(((Document.OpaqueDocument) document.document()).body()).isEqualTo("fallback body");
        assertThat(context.incidents())
            .extracting(FilterIncident::kind)
            .contains(FilterIncident.KIND_IR_FALLBACK);
    }

    @Test
    void envelopeProvenanceUsesAppliedAndStamp() throws Exception {
        Document document = Document.of(
            Document.DocumentKind.OPAQUE,
            "git status",
            "GitStatusFilter",
            0,
            true,
            new ExplainReport.ProvenanceInfo(true, "condense[filtered]"),
            new Document.OpaqueDocument("body"));
        JsonNode json = com.condense.core.Mappers.JSON.readTree(JsonRenderer.render(document));
        assertThat(json.get("provenance").get("applied").asBoolean()).isTrue();
        assertThat(json.get("provenance").get("stamp").asText()).isEqualTo("condense[filtered]");
        assertThat(json.has("stamp")).isFalse();
    }

    @Test
    void timeoutTerminationRoundTripsAndNormalDocumentsOmitIt() throws Exception {
        Document document = Document.opaque(
                "sleep",
                "passthrough",
                -1,
                false,
                Documents.provenance(false),
                "body")
            .withTermination(com.condense.core.TerminationReason.TIMEOUT);
        JsonNode json = com.condense.core.Mappers.JSON.readTree(JsonRenderer.render(document));
        assertThat(json.get("termination").asText()).isEqualTo("timeout");
        Document parsed = JsonRenderer.parse(json.toString());
        assertThat(parsed.termination()).isEqualTo(com.condense.core.TerminationReason.TIMEOUT);
        assertThat(parsed.childExitCode()).isEqualTo(-1);
    }

    private static Document typed(String id, Document.DocumentKind expected) throws Exception {
        FilterResult applied = CorpusRunner.apply(entry(id));
        assertThat(applied.document().kind()).as(id).isEqualTo(expected);
        return applied.document();
    }

    private static CorpusCatalog.Entry entry(String id) throws Exception {
        return CorpusCatalog.load().entries().stream()
            .filter(e -> id.equals(e.id()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("missing corpus row " + id));
    }
}
