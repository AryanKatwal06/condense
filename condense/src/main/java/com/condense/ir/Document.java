package com.condense.ir;

import com.condense.explain.ExplainReport;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Versioned diagnostics envelope. {@code schema_version} is 1. {@code kind} is a
 * closed set; renderers switch on it rather than discovering payload types.
 *
 * <p>The JSON field {@code document} holds the kind-specific payload. Unknown
 * keys are rejected by {@link JsonRenderer#parse(String)}, not by this record.
 */
@RegisterForReflection(targets = {
    Document.class,
    Document.DocumentKind.class,
    Document.TestDocument.class,
    Document.TestCase.class,
    Document.DiagnosticDocument.class,
    Document.Finding.class,
    Document.GroupCount.class,
    Document.DependencyDocument.class,
    Document.ResourceDocument.class,
    Document.ResourceRow.class,
    Document.OpaqueDocument.class
})
public record Document(
    int schemaVersion,
    DocumentKind kind,
    String command,
    String filter,
    int childExitCode,
    boolean wasFiltered,
    ExplainReport.ProvenanceInfo provenance,
    Object document
) {
    public static final int SCHEMA_VERSION = 1;

    public Document {
        schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
        kind = kind == null ? DocumentKind.OPAQUE : kind;
        command = command == null ? "" : command;
        filter = filter == null ? "" : filter;
        provenance = provenance == null
            ? new ExplainReport.ProvenanceInfo(false, null)
            : provenance;
        document = document == null ? new OpaqueDocument("") : document;
    }

    public static Document of(
            DocumentKind kind,
            String command,
            String filter,
            int childExitCode,
            boolean wasFiltered,
            ExplainReport.ProvenanceInfo provenance,
            Object payload
    ) {
        return new Document(
            SCHEMA_VERSION, kind, command, filter, childExitCode, wasFiltered, provenance, payload);
    }

    public static Document opaque(
            String command,
            String filter,
            int childExitCode,
            boolean wasFiltered,
            ExplainReport.ProvenanceInfo provenance,
            String body
    ) {
        return of(
            DocumentKind.OPAQUE,
            command,
            filter,
            childExitCode,
            wasFiltered,
            provenance,
            new OpaqueDocument(body == null ? "" : body));
    }

    public enum DocumentKind {
        TEST("test"),
        DIAGNOSTIC("diagnostic"),
        DEPENDENCY("dependency"),
        RESOURCE("resource"),
        OPAQUE("opaque");

        private final String wire;

        DocumentKind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }

        @JsonCreator
        public static DocumentKind fromWire(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("document kind is required");
            }
            String key = value.trim().toLowerCase(Locale.ROOT);
            for (DocumentKind kind : values()) {
                if (kind.wire.equals(key)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown document kind: " + value);
        }
    }

    @RegisterForReflection
    public record TestDocument(
        List<TestCase> cases,
        int passed,
        int failed,
        int errors,
        List<String> lines,
        String emptyFallback
    ) {
        public TestDocument {
            cases = copy(cases);
            lines = copy(lines);
            emptyFallback = emptyFallback == null ? "" : emptyFallback;
        }
    }

    @RegisterForReflection
    public record TestCase(String name, String status, String detail) {
        public TestCase {
            name = name == null ? "" : name;
            status = status == null ? "" : status;
            detail = detail == null ? "" : detail;
        }
    }

    @RegisterForReflection
    public record DiagnosticDocument(
        List<Finding> findings,
        int errors,
        int warnings,
        List<GroupCount> groups,
        String groupStyle,
        boolean clean
    ) {
        public static final String GROUP_ALIGNED = "aligned";
        public static final String GROUP_COLON = "colon";

        public DiagnosticDocument {
            findings = copy(findings);
            groups = copy(groups);
            groupStyle = groupStyle == null || groupStyle.isBlank() ? GROUP_ALIGNED : groupStyle;
        }
    }

    @RegisterForReflection
    public record Finding(String file, Integer line, String code, String message, String severity) {
        public Finding {
            file = file == null ? "" : file;
            code = code == null ? "" : code;
            message = message == null ? "" : message;
            severity = severity == null ? "" : severity;
        }
    }

    @RegisterForReflection
    public record GroupCount(String key, int count) {
        public GroupCount {
            key = key == null ? "" : key;
        }
    }

    @RegisterForReflection
    public record DependencyDocument(
        Integer addedPackages,
        Integer vulnerabilityCount,
        String vulnerabilityText,
        List<String> irrevocable,
        boolean failed
    ) {
        public DependencyDocument {
            irrevocable = copy(irrevocable);
            vulnerabilityText = vulnerabilityText == null ? "" : vulnerabilityText;
        }
    }

    @RegisterForReflection
    public record ResourceDocument(
        List<ResourceRow> rows,
        boolean empty
    ) {
        public ResourceDocument {
            rows = copy(rows);
        }
    }

    @RegisterForReflection
    public record ResourceRow(String id, String image, String status, String name, String raw) {
        public ResourceRow {
            id = id == null ? "" : id;
            image = image == null ? "" : image;
            status = status == null ? "" : status;
            name = name == null ? "" : name;
            raw = raw == null ? "" : raw;
        }
    }

    @RegisterForReflection
    public record OpaqueDocument(String body) {
        public OpaqueDocument {
            body = body == null ? "" : body;
        }
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
