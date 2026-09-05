package com.condense.core;

import com.condense.filter.pipeline.FilterIncident;
import com.condense.ir.Document;
import com.condense.trust.Provenance;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * The output produced by a {@link FilterStrategy} after compressing a command's
 * raw output.
 *
 * @param output      the compressed output string to print to stdout
 * @param rawTokens   estimated token count of the original command output
 * @param outTokens   estimated token count of {@code output}
 * @param wasFiltered true if any compression was applied; false if output is
 *                    identical to raw (passthrough scenario)
 * @param incidents   fail-open events to persist; empty for intentional passthrough
 * @param document    optional typed diagnostics document; null until apply/stream attach it
 */
public record FilterResult(
    String output,
    int rawTokens,
    int outTokens,
    boolean wasFiltered,
    List<FilterIncident> incidents,
    Document document
) {

    private static final Logger log = Logger.getLogger(FilterResult.class);

    public FilterResult {
        incidents = incidents == null || incidents.isEmpty() ? List.of() : List.copyOf(incidents);
    }

    public FilterResult(String output, int rawTokens, int outTokens, boolean wasFiltered) {
        this(output, rawTokens, outTokens, wasFiltered, List.of(), null);
    }

    public FilterResult(String output, int rawTokens, int outTokens, boolean wasFiltered, List<FilterIncident> incidents) {
        this(output, rawTokens, outTokens, wasFiltered, incidents, null);
    }

    public FilterResult withDocument(Document attached) {
        return new FilterResult(output, rawTokens, outTokens, wasFiltered, incidents, attached);
    }

    public FilterResult withRenderedOutput(String rendered) {
        String text = rendered == null ? "" : rendered;
        return new FilterResult(
            text, rawTokens, TokenCounter.count(text), wasFiltered, incidents, document);
    }

    /** Percentage of tokens saved, 0–100. Returns 0 if rawTokens is 0. */
    public int savingsPct() {
        if (rawTokens == 0) return 0;
        return (int) (100L * (rawTokens - outTokens) / rawTokens);
    }

    /**
     * Convenience factory: build a FilterResult for a passthrough (no filtering).
     * rawTokens == outTokens, wasFiltered == false.
     */
    public static FilterResult passthrough(ExecutionResult result) {
        int tokens = 0;
        try {
            if (result.stdoutFile() != null) tokens += TokenCounter.count(result.stdoutFile());
            if (result.stderrFile() != null) tokens += TokenCounter.count(result.stderrFile());
        } catch (Exception e) {
            log.debugf("Token counting failed in passthrough: %s", e.getMessage());
        }
        return new FilterResult(Provenance.passthrough(result.combined()), tokens, tokens, false);
    }

    /**
     * Apply-level fail-open: same visible output as {@link #passthrough} plus a persistable incident.
     */
    public static FilterResult fallbackPassthrough(ExecutionResult result, String filterName, String detail) {
        int tokens = 0;
        try {
            if (result.stdoutFile() != null) tokens += TokenCounter.count(result.stdoutFile());
            if (result.stderrFile() != null) tokens += TokenCounter.count(result.stderrFile());
        } catch (Exception e) {
            log.debugf("Token counting failed in fallbackPassthrough: %s", e.getMessage());
        }
        return new FilterResult(
            Provenance.passthrough(result.combined()),
            tokens,
            tokens,
            false,
            List.of(FilterIncident.applyFallback(filterName, detail))
        );
    }

    /**
     * Convenience factory: build a FilterResult for a successfully compressed output.
     */
    public static FilterResult of(ExecutionResult result, String filteredOutput) {
        return of(result, filteredOutput, List.of());
    }

    public static FilterResult of(ExecutionResult result, String filteredOutput, List<FilterIncident> incidents) {
        int rawTokens = 0;
        try {
            if (result.stdoutFile() != null) rawTokens += TokenCounter.count(result.stdoutFile());
            if (result.stderrFile() != null) rawTokens += TokenCounter.count(result.stderrFile());
        } catch (Exception e) {
            log.debugf("Token counting failed in of: %s", e.getMessage());
        }

        String stamped = Provenance.stamp(filteredOutput);
        return new FilterResult(
            stamped,
            rawTokens,
            TokenCounter.count(stamped),
            true,
            incidents
        );
    }
}
