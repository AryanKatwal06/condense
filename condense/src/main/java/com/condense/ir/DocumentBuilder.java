package com.condense.ir;

import com.condense.explain.ExplainReport;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Mutable sidecar on {@link com.condense.filter.pipeline.FilterContext}.
 * Exemplar stages populate one payload; everyone else leaves it empty so
 * {@link Documents} can wrap the existing text as {@code kind=opaque}.
 */
@RegisterForReflection
public final class DocumentBuilder {

    private Document.DocumentKind kind;
    private Object payload;
    private RuntimeException forcedFailure;

    public boolean isPopulated() {
        return kind != null && payload != null;
    }

    public void test(Document.TestDocument document) {
        set(Document.DocumentKind.TEST, document);
    }

    public void diagnostic(Document.DiagnosticDocument document) {
        set(Document.DocumentKind.DIAGNOSTIC, document);
    }

    public void dependency(Document.DependencyDocument document) {
        set(Document.DocumentKind.DEPENDENCY, document);
    }

    public void resource(Document.ResourceDocument document) {
        set(Document.DocumentKind.RESOURCE, document);
    }

    public void opaque(Document.OpaqueDocument document) {
        set(Document.DocumentKind.OPAQUE, document);
    }

    /**
     * Test hook for the fail-open path. The next {@link #build} throws, then
     * the flag clears so later builds behave normally.
     */
    public void failNextBuild(RuntimeException error) {
        this.forcedFailure = error;
    }

    public Document build(
            String command,
            String filter,
            int childExitCode,
            boolean wasFiltered,
            ExplainReport.ProvenanceInfo provenance
    ) {
        if (forcedFailure != null) {
            RuntimeException error = forcedFailure;
            forcedFailure = null;
            throw error;
        }
        if (!isPopulated()) {
            return null;
        }
        return Document.of(kind, command, filter, childExitCode, wasFiltered, provenance, payload);
    }

    private void set(Document.DocumentKind nextKind, Object nextPayload) {
        this.kind = nextKind;
        this.payload = nextPayload;
    }
}
