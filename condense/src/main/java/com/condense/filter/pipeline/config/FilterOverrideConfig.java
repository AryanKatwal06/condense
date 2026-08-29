package com.condense.filter.pipeline.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Root and nested configuration structures for declarative filter overrides.
 */
public final class FilterOverrideConfig {

    private FilterOverrideConfig() {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileConfig(
        @JsonProperty("filters")
        Map<String, FilterDef> filters
    ) {
        public FileConfig {
            if (filters == null) {
                filters = Collections.emptyMap();
            }
        }

        public FileConfig() {
            this(Collections.emptyMap());
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
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
    @JsonIgnoreProperties(ignoreUnknown = true)
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
        Map<String, String> defaultActions
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
            this(null, null, null, null, null, Collections.emptyList(), Collections.emptyMap());
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
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
