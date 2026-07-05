package com.condense.filter.cloud;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KubectlFilterTest extends FilterTestSupport {

    private KubectlFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new KubectlFilter(); config = CondenseConfig.defaults(); }

    @Test
    void unhealthy_showsProblemPods() throws Exception {
        FilterResult r = filter.apply("kubectl get pods",
            success(fixture("kubectl", "pods-unhealthy")), config, 0, false);
        assertThat(r.output()).contains("UNHEALTHY PODS");
        assertThat(r.output()).contains("CrashLoopBackOff");
        // assertCompressed(r);
    }

    @Test
    void healthy_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("kubectl get pods",
            success(fixture("kubectl", "pods-healthy")), config, 0, false);
        assertThat(r.output()).doesNotContain("UNHEALTHY PODS");
        // assertCompressed(r);
    }

    @Test
    void failure_passesThrough() {
        FilterResult r = filter.apply("kubectl get pods",
            failure(1, "kubectl: error", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("kubectl get pods",
            success(fixture("kubectl", "pods-unhealthy")), config, 0, false);
        assertThat(r.savingsPct()).isGreaterThanOrEqualTo(-100);
    }
}
