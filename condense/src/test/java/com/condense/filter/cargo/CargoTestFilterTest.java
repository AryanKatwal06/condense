package com.condense.filter.cargo;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CargoTestFilterTest extends FilterTestSupport {

    private CargoTestFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new CargoTestFilter(); config = CondenseConfig.defaults(); }

    @Test
    void withFailures_showsOnlyFailureNames() throws Exception {
        FilterResult r = filter.apply("cargo test",
            new ExecutionResult(101, fixture("cargo-test", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).contains("FAILED: tests::test_multiply");
        assertThat(r.output()).contains("FAILED: tests::test_modulo");
        assertThat(r.output()).doesNotContain("ok");
        assertCompressed(r);
    }

    @Test
    void allPassing_showsSuccessLine() throws Exception {
        FilterResult r = filter.apply("cargo test",
            success(fixture("cargo-test", "passing")), config, 0, false);
        assertThat(r.output()).containsAnyOf("passed", "✓");
        assertCompressed(r);
    }

    @Test
    void failureCount_isCorrect() throws Exception {
        FilterResult r = filter.apply("cargo test",
            new ExecutionResult(101, fixture("cargo-test", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).contains("2 failure(s)");
    }
}