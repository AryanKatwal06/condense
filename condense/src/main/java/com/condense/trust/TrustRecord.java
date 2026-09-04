package com.condense.trust;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;

/**
 * One trusted project override file in {@code trust.json}.
 */
@RegisterForReflection
public record TrustRecord(
    @JsonProperty("path")
    String path,

    @JsonProperty("sha256")
    String sha256,

    @JsonProperty("capabilities")
    List<String> capabilities,

    @JsonProperty("trusted_at")
    String trustedAt
) {
    public TrustRecord {
        if (capabilities == null) {
            capabilities = Collections.emptyList();
        }
    }

    public TrustRecord() {
        this(null, null, Collections.emptyList(), null);
    }
}
