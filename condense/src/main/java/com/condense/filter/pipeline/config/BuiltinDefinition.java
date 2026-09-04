package com.condense.filter.pipeline.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;

/**
 * Schema v1 builtin filter definition loaded from {@code classpath:filters/<name>.toml}.
 */
@RegisterForReflection
public record BuiltinDefinition(
    @JsonProperty("schema_version")
    Integer schemaVersion,

    @JsonProperty("name")
    String name,

    @JsonProperty("commands")
    List<String> commands,

    @JsonProperty("stages")
    List<FilterOverrideConfig.StageDef> stages,

    @JsonProperty("tests")
    List<InlineTest> tests
) {
    public BuiltinDefinition {
        if (commands == null) {
            commands = Collections.emptyList();
        }
        if (stages == null) {
            stages = Collections.emptyList();
        }
        if (tests == null) {
            tests = Collections.emptyList();
        }
    }

    public BuiltinDefinition() {
        this(null, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    @RegisterForReflection
    public record InlineTest(
        @JsonProperty("id")
        String id,

        @JsonProperty("input")
        String input,

        @JsonProperty("expected")
        String expected
    ) {
        public InlineTest() {
            this(null, null, null);
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
            this(null, Collections.emptyList());
        }
    }
}
