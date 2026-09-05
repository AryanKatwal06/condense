package com.condense.ir;

import com.condense.core.Mappers;
import com.condense.explain.ExplainReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Schema-1 JSON renderer and parser. Kind is a closed switch — no reflective
 * payload discovery. Unknown keys fail on parse.
 */
@RegisterForReflection
public final class JsonRenderer {

    private static final ObjectMapper STRICT = Mappers.JSON.copy()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private JsonRenderer() {}

    public static String render(Document document) {
        try {
            return Mappers.JSON.writeValueAsString(document == null
                ? Document.opaque("", "", 0, false, Documents.provenance(false), "")
                : document);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Document parse(String json) {
        try {
            EnvelopeWire envelope = STRICT.readValue(json, EnvelopeWire.class);
            if (envelope.schemaVersion() != Document.SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                    "unsupported schema_version: " + envelope.schemaVersion());
            }
            Document.DocumentKind kind = Document.DocumentKind.fromWire(envelope.kind());
            Object payload = parsePayload(kind, envelope.document());
            return Document.of(
                kind,
                envelope.command(),
                envelope.filter(),
                envelope.childExitCode(),
                envelope.wasFiltered(),
                envelope.provenance() == null
                    ? Documents.provenance(envelope.wasFiltered())
                    : envelope.provenance(),
                payload,
                envelope.termination()
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Object parsePayload(Document.DocumentKind kind, JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("document payload is required");
        }
        return switch (kind) {
            case TEST -> STRICT.treeToValue(node, Document.TestDocument.class);
            case DIAGNOSTIC -> STRICT.treeToValue(node, Document.DiagnosticDocument.class);
            case DEPENDENCY -> STRICT.treeToValue(node, Document.DependencyDocument.class);
            case RESOURCE -> STRICT.treeToValue(node, Document.ResourceDocument.class);
            case OPAQUE -> STRICT.treeToValue(node, Document.OpaqueDocument.class);
        };
    }

    @RegisterForReflection
    public record EnvelopeWire(
        int schemaVersion,
        String kind,
        String command,
        String filter,
        int childExitCode,
        boolean wasFiltered,
        ExplainReport.ProvenanceInfo provenance,
        JsonNode document,
        com.condense.core.TerminationReason termination
    ) {}
}
