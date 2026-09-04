package com.condense.trust;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;

/**
 * Root document for {@code {configDir}/trust.json}.
 */
@RegisterForReflection
public record TrustFile(
    @JsonProperty("schema_version")
    Integer schemaVersion,

    @JsonProperty("entries")
    List<TrustRecord> entries
) {
    public TrustFile {
        if (entries == null) {
            entries = Collections.emptyList();
        }
    }

    public TrustFile() {
        this(null, Collections.emptyList());
    }
}
