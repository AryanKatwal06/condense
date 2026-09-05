package com.condense.discover;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;

/**
 * Schema v1 builtin discovery rule loaded from {@code classpath:discover/<name>.toml}.
 */
@RegisterForReflection
public record DiscoverDefinition(
    @JsonProperty("schema_version")
    Integer schemaVersion,

    @JsonProperty("name")
    String name,

    @JsonProperty("family")
    String family,

    @JsonProperty("priority")
    Integer priority,

    @JsonProperty("signals")
    List<String> signals,

    @JsonProperty("extras")
    List<Extra> extras,

    @JsonProperty("recommend")
    List<String> recommend,

    @JsonProperty("workspace_git")
    Boolean workspaceGit
) {
    public static final int SCHEMA_VERSION = 1;

    public DiscoverDefinition {
        if (signals == null) {
            signals = Collections.emptyList();
        }
        if (extras == null) {
            extras = Collections.emptyList();
        }
        if (recommend == null) {
            recommend = Collections.emptyList();
        }
    }

    public DiscoverDefinition() {
        this(null, null, null, null, List.of(), List.of(), List.of(), null);
    }

    public boolean workspaceGitMarker() {
        return Boolean.TRUE.equals(workspaceGit);
    }

    @RegisterForReflection
    public record Extra(
        @JsonProperty("path")
        String path,

        @JsonProperty("contains")
        List<String> contains
    ) {
        public Extra {
            if (contains == null) {
                contains = Collections.emptyList();
            }
        }

        public Extra() {
            this(null, List.of());
        }
    }

    @RegisterForReflection
    public record Index(
        @JsonProperty("schema_version")
        Integer schemaVersion,

        @JsonProperty("definitions")
        List<String> definitions
    ) {
        public Index {
            if (definitions == null) {
                definitions = Collections.emptyList();
            }
        }

        public Index() {
            this(null, List.of());
        }
    }
}
