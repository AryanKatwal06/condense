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
        assertThat(Provenance.neutralize("condense[read]"))
            .isEqualTo("condense[quoted]");
    }

    @Test
    void stampReadPrefixesNeutralizedBody() {
        assertThat(Provenance.stampRead("hello")).isEqualTo("condense[read]\nhello");
        assertThat(Provenance.stampRead("condense[read]")).isEqualTo("condense[read]\ncondense[quoted]");
    }

    @Test
    void passthroughDoesNotStamp() {
        assertThat(Provenance.passthrough("raw")).isEqualTo("raw");
        assertThat(Provenance.passthrough("condense[filtered]")).isEqualTo("condense[quoted]");
    }
}
