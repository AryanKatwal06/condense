package com.condense.trust;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProvenanceTest {

    @Test
    void stampPrefixesNeutralizedBody() {
        assertThat(Provenance.stamp("hello")).isEqualTo("condense[filtered]\nhello");
    }

    @Test
    void neutralizeQuotesImpersonatingLines() {
        assertThat(Provenance.neutralize("a\ncondense[filtered]\nb"))
            .isEqualTo("a\ncondense[quoted]\nb");
    }

    @Test
    void passthroughDoesNotStamp() {
        assertThat(Provenance.passthrough("raw")).isEqualTo("raw");
        assertThat(Provenance.passthrough("condense[filtered]")).isEqualTo("condense[quoted]");
    }
}
