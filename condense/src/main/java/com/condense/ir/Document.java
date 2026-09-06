package com.condense.ir;

import com.condense.core.TerminationReason;
import com.condense.explain.ExplainReport;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
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
    Document.OpaqueDocument.class,
    TerminationReason.class
})
public record Document(
    int schemaVersion,
    DocumentKind kind,
    String command,
    String filter,
    int childExitCode,
    boolean wasFiltered,
    ExplainReport.ProvenanceInfo provenance,
    Object document,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    TerminationReason termination
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
        termination = (termination == null || termination == TerminationReason.CHILD_EXIT)
            ? null
            : termination;
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
        return of(kind, command, filter, childExitCode, wasFiltered, provenance, payload, null);
    }

    public static Document of(
            DocumentKind kind,
            String command,
            String filter,
            int childExitCode,
            boolean wasFiltered,
            ExplainReport.ProvenanceInfo provenance,
            Object payload,
            TerminationReason termination
    ) {
        return new Document(
            SCHEMA_VERSION, kind, command, filter, childExitCode, wasFiltered, provenance, payload, termination);
    }

    public Document withTermination(TerminationReason reason) {
        TerminationReason normalized = (reason == null || reason == TerminationReason.CHILD_EXIT)
            ? null
            : reason;
        if (termination == normalized) {
            return this;
        }
        return new Document(
            schemaVersion, kind, command, filter, childExitCode, wasFiltered, provenance, document, normalized);
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
            new OpaqueDocument(body == null ? "" : body),
            null);
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
        String emptyFallback,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer skipped,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer total,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String tool
    ) {
        public TestDocument {
            cases = copy(cases);
            lines = copy(lines);
            emptyFallback = emptyFallback == null ? "" : emptyFallback;
            tool = tool == null || tool.isBlank() ? null : tool;
        }

        public TestDocument(
                List<TestCase> cases,
                int passed,
                int failed,
                int errors,
                List<String> lines,
                String emptyFallback
        ) {
            this(cases, passed, failed, errors, lines, emptyFallback, null, null, null);
        }
    }

    @RegisterForReflection
    public record TestCase(
        String name,
        String status,
        String detail,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String file,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer durationMs,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String stack
    ) {
        public TestCase {
            name = name == null ? "" : name;
            status = status == null ? "" : status;
            detail = detail == null ? "" : detail;
            file = file == null || file.isBlank() ? null : file;
            stack = stack == null || stack.isBlank() ? null : stack;
        }

        public TestCase(String name, String status, String detail) {
            this(name, status, detail, null, null, null);
        }
    }

    @RegisterForReflection
    public record DiagnosticDocument(
        List<Finding> findings,
        int errors,
        int warnings,
        List<GroupCount> groups,
        String groupStyle,
        boolean clean,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String tool
    ) {
        public static final String GROUP_ALIGNED = "aligned";
        public static final String GROUP_COLON = "colon";

        public DiagnosticDocument {
            findings = copy(findings);
            groups = copy(groups);
            groupStyle = groupStyle == null || groupStyle.isBlank() ? GROUP_ALIGNED : groupStyle;
            tool = tool == null || tool.isBlank() ? null : tool;
        }

        public DiagnosticDocument(
            List<Finding> findings,
            int errors,
            int warnings,
            List<GroupCount> groups,
            String groupStyle,
            boolean clean
        ) {
            this(findings, errors, warnings, groups, groupStyle, clean, null);
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
        boolean empty,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String format,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer add,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer change,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer destroy,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer replace,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean capped
    ) {
        public static final String FORMAT_CONTAINERS = "containers";
        public static final String FORMAT_INFRA = "infra";

        public ResourceDocument {
            rows = copy(rows);
            format = format == null || format.isBlank() ? null : format;
        }

        public ResourceDocument(List<ResourceRow> rows, boolean empty) {
            this(rows, empty, null, null, null, null, null, null);
        }

        public boolean infra() {
            return FORMAT_INFRA.equals(format);
        }
    }

    @RegisterForReflection
    public record ResourceRow(
        String id,
        String image,
        String status,
        String name,
        String raw,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String address,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String action,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reason,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String resourceType
    ) {
        public ResourceRow {
            id = id == null ? "" : id;
            image = image == null ? "" : image;
            status = status == null ? "" : status;
            name = name == null ? "" : name;
            raw = raw == null ? "" : raw;
            address = address == null || address.isBlank() ? null : address;
            action = action == null || action.isBlank() ? null : action;
            reason = reason == null || reason.isBlank() ? null : reason;
            resourceType = resourceType == null || resourceType.isBlank() ? null : resourceType;
        }

        public ResourceRow(String id, String image, String status, String name, String raw) {
            this(id, image, status, name, raw, null, null, null, null);
        }

        public static ResourceRow infra(String address, String action, String reason, String resourceType) {
            return new ResourceRow("", "", "", "", "", address, action, reason, resourceType);
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
