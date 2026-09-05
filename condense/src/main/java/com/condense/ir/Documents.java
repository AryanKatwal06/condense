package com.condense.ir;

import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.explain.ExplainReport;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterIncident;
import com.condense.trust.Provenance;

/**
 * Finishes a {@link Document} from a pipeline sidecar, or wraps existing text
 * as {@code kind=opaque}. IR construction never changes a child exit code.
 */
public final class Documents {

    private Documents() {}

    public static Document fromContext(
            FilterContext context,
            String command,
            String filter,
            ExecutionResult result,
            boolean wasFiltered,
            String fallbackBody
    ) {
        ExplainReport.ProvenanceInfo provenance = provenance(wasFiltered);
        int exit = result == null ? 0 : result.exitCode();
        try {
            DocumentBuilder builder = context == null ? null : context.documentBuilder();
            if (builder != null && builder.isPopulated()) {
                Document built = builder.build(command, filter, exit, wasFiltered, provenance);
                if (built != null) {
                    return withTermination(built, result);
                }
            }
            return withTermination(
                Document.opaque(command, filter, exit, wasFiltered, provenance, fallbackBody), result);
        } catch (RuntimeException e) {
            if (context != null) {
                context.recordIncident(FilterIncident.irFallback("document", e.getMessage()));
            }
            return withTermination(
                Document.opaque(command, filter, exit, wasFiltered, provenance, fallbackBody), result);
        }
    }

    public static Document fromResult(
            String command,
            String filter,
            ExecutionResult result,
            FilterResult filtered
    ) {
        if (filtered != null && filtered.document() != null) {
            return withTermination(filtered.document(), result);
        }
        boolean wasFiltered = filtered != null && filtered.wasFiltered();
        String body = opaqueBody(filtered);
        int exit = result == null ? 0 : result.exitCode();
        return withTermination(
            Document.opaque(command, filter, exit, wasFiltered, provenance(wasFiltered), body), result);
    }

    static Document withTermination(Document document, ExecutionResult result) {
        if (document == null) {
            return null;
        }
        return document.withTermination(result == null ? null : result.termination());
    }

    public static ExplainReport.ProvenanceInfo provenance(boolean wasFiltered) {
        return wasFiltered
            ? new ExplainReport.ProvenanceInfo(true, Provenance.STAMP)
            : new ExplainReport.ProvenanceInfo(false, null);
    }

    /**
     * Opaque body is the visible text without a fake stamp first line.
     * Provenance on the envelope carries {@code condense[filtered]}.
     */
    public static String opaqueBody(FilterResult filtered) {
        if (filtered == null || filtered.output() == null) {
            return "";
        }
        return stripStamp(filtered.output());
    }

    public static String stripStamp(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String value = text;
        if (value.startsWith(Provenance.STAMP + "\n")) {
            return value.substring(Provenance.STAMP.length() + 1);
        }
        if (Provenance.STAMP.equals(value)) {
            return "";
        }
        return value;
    }
}
