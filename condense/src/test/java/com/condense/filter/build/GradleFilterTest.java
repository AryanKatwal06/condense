package com.condense.filter.build;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import com.condense.filter.pipeline.FilterIncident;
import com.condense.trust.Provenance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GradleFilterTest extends FilterTestSupport {

    private GradleFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new GradleFilter(); config = CondenseConfig.defaults(); }

    @Test
    void failure_showsError() throws Exception {
        ExecutionResult execution = failure(1, fixture("gradle", "failure"), "");
        FilterResult r = filter.apply("gradle build", execution, config, 0, false);
        assertThat(r.output()).contains("FAILED");
        assertFidelityFallback(r, execution);
    }

    @Test
    void success_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("gradle build",
            success(fixture("gradle", "success")), config, 0, false);
        assertThat(r.output()).containsAnyOf("SUCCESS", "✓", "SUCCESSFUL");
        assertCompressed(r);
    }

    @Test
    void empty_passesThrough() {
        FilterResult r = filter.apply("gradle build",
            failure(1, "gradle: error", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void failureFixtureFallsBackToVerbatimOutput() throws Exception {
        // The guard rejects the lossy compiler-diagnostic summary, so the
        // failure fixture must come back byte-for-byte instead of compressed.
        ExecutionResult execution = failure(1, fixture("gradle", "failure"), "");
        FilterResult r = filter.apply("gradle build", execution, config, 0, false);
        assertFidelityFallback(r, execution);
        assertThat(r.savingsPct()).isZero();
    }

    private void assertFidelityFallback(FilterResult r, ExecutionResult execution) {
        assertThat(r.wasFiltered()).isFalse();
        assertThat(r.output()).isEqualTo(Provenance.passthrough(execution.combined()));
        assertThat(r.incidents())
            .as("fidelity fallback must be recorded as an apply incident")
            .anyMatch(i -> FilterIncident.KIND_APPLY_FALLBACK.equals(i.kind()));
    }
}
