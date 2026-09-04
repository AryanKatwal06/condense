package com.condense.trust;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CiIndicatorTest {

    @Test
    void hatchRequiresBothFlagAndCiIndicator() {
        assertThat(CiIndicator.projectFiltersHatchArmed(Map.of(
            CiIndicator.TRUST_PROJECT_FILTERS, "1"
        )::get)).isFalse();

        assertThat(CiIndicator.projectFiltersHatchArmed(Map.of(
            CiIndicator.TRUST_PROJECT_FILTERS, "1",
            "GITHUB_ACTIONS", "true"
        )::get)).isTrue();

        assertThat(CiIndicator.projectFiltersHatchArmed(Map.of(
            "GITHUB_ACTIONS", "true"
        )::get)).isFalse();
    }

    @Test
    void hatchCapabilitiesDefaultToReduceAndIgnoreExtraWithoutCi() {
        assertThat(CiIndicator.hatchCapabilities(Map.of(
            CiIndicator.TRUST_PROJECT_CAPABILITIES, "reshape,rewrite"
        )::get)).containsExactly(Capability.REDUCE);

        assertThat(CiIndicator.hatchCapabilities(Map.of(
            "CI", "true",
            CiIndicator.TRUST_PROJECT_CAPABILITIES, "reshape,rewrite"
        )::get)).contains(Capability.REDUCE, Capability.RESHAPE, Capability.REWRITE);
    }

    @Test
    void condoseConfigDirIsNotACiSignal() {
        assertThat(CiIndicator.isCi(Map.of("CONDENSE_CONFIG_DIR", "/tmp/x")::get)).isFalse();
    }
}
