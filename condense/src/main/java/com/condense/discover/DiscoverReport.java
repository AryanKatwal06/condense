package com.condense.discover;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;

/**
 * Schema-1 discovery recommendation. Recommend-only; does not change dispatch.
 */
@RegisterForReflection
public record DiscoverReport(
    @JsonProperty("schema_version")
    int schemaVersion,

    @JsonProperty("root")
    String root,

    @JsonProperty("families")
    List<FamilyHit> families,

    @JsonProperty("recommend")
    List<String> recommend,

    @JsonProperty("files_probed")
    int filesProbed,

    @JsonProperty("files_read")
    int filesRead,

    @JsonProperty("bytes_read")
    long bytesRead,

    @JsonProperty("truncated")
    boolean truncated,

    @JsonProperty("warnings")
    List<String> warnings,

    @JsonProperty("error")
    String error
) {
    public static final int SCHEMA_VERSION = 1;

    public DiscoverReport {
        if (families == null) {
            families = Collections.emptyList();
        }
        if (recommend == null) {
            recommend = Collections.emptyList();
        }
        if (warnings == null) {
            warnings = Collections.emptyList();
        }
    }

    public boolean failed() {
        return error != null && !error.isBlank();
    }

    public static DiscoverReport failure(String root, String error) {
        return new DiscoverReport(
            SCHEMA_VERSION, root, List.of(), List.of(), 0, 0, 0L, false, List.of(), error);
    }

    @RegisterForReflection
    public record FamilyHit(
        @JsonProperty("family")
        String family,

        @JsonProperty("rule")
        String rule,

        @JsonProperty("signals")
        List<String> signals,

        @JsonProperty("recommend")
        List<String> recommend
    ) {
        public FamilyHit {
            if (signals == null) {
                signals = Collections.emptyList();
            }
            if (recommend == null) {
                recommend = Collections.emptyList();
            }
        }
    }
}
