package com.condense.filter.cargo;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import com.condense.filter.pipeline.FilterIncident;
import com.condense.trust.Provenance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CargoTestFilterTest extends FilterTestSupport {

    private CargoTestFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new CargoTestFilter(); config = CondenseConfig.defaults(); }

    @Test
    void withFailures_preservesPanicOutputVerbatim() throws Exception {
        // The guard rejects a summary that drops panic locations, so the raw
        // cargo output comes back untouched with an apply-fallback incident.
        ExecutionResult execution = new ExecutionResult(101, fixture("cargo-test", "typical"), "", 500L);
        FilterResult r = filter.apply("cargo test", execution, config, 0, false);
        assertThat(r.output()).contains("test tests::test_multiply ... FAILED");
        assertThat(r.output()).contains("panicked at 'division by zero'");
        assertThat(r.output()).contains("src/lib.rs:20:9");
        assertFidelityFallback(r, execution);
    }

    @Test
    void allPassing_showsSuccessLine() throws Exception {
        FilterResult r = filter.apply("cargo test",
            success(fixture("cargo-test", "passing")), config, 0, false);
        assertThat(r.output()).containsAnyOf("passed", "✓");
        assertCompressed(r);
    }

    @Test
    void failureCount_keepsTheRawTally() throws Exception {
        ExecutionResult execution = new ExecutionResult(101, fixture("cargo-test", "typical"), "", 500L);
        FilterResult r = filter.apply("cargo test", execution, config, 0, false);
        assertThat(r.output()).contains("test result: FAILED. 1 passed; 2 failed");
        assertFidelityFallback(r, execution);
    }

    private void assertFidelityFallback(FilterResult r, ExecutionResult execution) {
        assertThat(r.wasFiltered()).isFalse();
        assertThat(r.output()).isEqualTo(Provenance.passthrough(execution.combined()));
        assertThat(r.incidents())
            .as("fidelity fallback must be recorded as an apply incident")
            .anyMatch(i -> FilterIncident.KIND_APPLY_FALLBACK.equals(i.kind()));
    }
}