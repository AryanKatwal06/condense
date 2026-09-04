package com.condense.filter.pipeline.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Root and nested configuration structures for declarative filter overrides (schema v1).
 */
public final class FilterOverrideConfig {

    public static final int SCHEMA_VERSION = 1;

    private FilterOverrideConfig() {}

    @RegisterForReflection
    public record FileConfig(
        @JsonProperty("schema_version")
        Integer schemaVersion,

        @JsonProperty("filters")
        Map<String, FilterDef> filters
    ) {
        public FileConfig {
            if (filters == null) {
                filters = Collections.emptyMap();
            }
        }

        public FileConfig() {
            this(null, Collections.emptyMap());
        }
    }

    @RegisterForReflection
    public record FilterDef(
        @JsonProperty("stages")
        List<StageDef> stages
    ) {
        public FilterDef {
            if (stages == null) {
                stages = Collections.emptyList();
            }
        }

        public FilterDef() {
            this(Collections.emptyList());
        }
    }

    @RegisterForReflection
    public record StageDef(
        @JsonProperty("strategy")
        String strategy,

        @JsonProperty("window_size")
        Integer windowSize,

        @JsonProperty("pattern")
        String pattern,

        @JsonProperty("include_other")
        Boolean includeOther,

        @JsonProperty("initial_state")
        String initialState,

        @JsonProperty("transitions")
        List<TransitionDef> transitions,

        @JsonProperty("default_actions")
        Map<String, String> defaultActions,

        @JsonProperty("max_lines")
        Integer maxLines,

        @JsonProperty("skip_blank")
        Boolean skipBlank,

        @JsonProperty("header_only_when_truncating")
        Boolean headerOnlyWhenTruncating,

        @JsonProperty("head")
        Integer head,

        @JsonProperty("tail")
        Integer tail,

        @JsonProperty("key")
        String key,

        @JsonProperty("header")
        String header,

        @JsonProperty("top_n")
        Integer topN,

        @JsonProperty("format")
        String format,

        @JsonProperty("fallback")
        String fallback
    ) {
        public StageDef {
            if (transitions == null) {
                transitions = Collections.emptyList();
            }
            if (defaultActions == null) {
                defaultActions = Collections.emptyMap();
            }
        }

        public StageDef() {
            this(null, null, null, null, null, Collections.emptyList(), Collections.emptyMap(),
                null, null, null, null, null, null, null, null, null, null);
        }
    }

    @RegisterForReflection
    public record TransitionDef(
        @JsonProperty("from_state")
        String fromState,

        @JsonProperty("pattern")
        String pattern,

        @JsonProperty("action")
        String action,

        @JsonProperty("next_state")
        String nextState
    ) {
        public TransitionDef() {
            this(null, null, null, null);
        }
    }
}
