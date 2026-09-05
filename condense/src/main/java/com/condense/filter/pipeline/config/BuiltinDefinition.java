package com.condense.filter.pipeline.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;

/**
 * Schema v1 builtin filter definition loaded from {@code classpath:filters/<name>.toml}.
 *
 * <p>{@code select_input} and {@code gate} are optional builtin-only fields.
 * They are unknown keys in user override files.
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
    List<InlineTest> tests,

    @JsonProperty("select_input")
    String selectInput,

    @JsonProperty("gate")
    Gate gate
) {
    public static final String SELECT_STDOUT_OR_STDERR = "stdout_or_stderr";
    public static final String SELECT_STDERR_THEN_STDOUT = "stderr_then_stdout";
    public static final String SELECT_STDOUT = "stdout";
    public static final String SELECT_STDERR = "stderr";

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
        this(null, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            null, null);
    }

    @RegisterForReflection
    public record Gate(
        @JsonProperty("passthrough_verbose")
        Integer passthroughVerbose,

        @JsonProperty("passthrough_max_lines")
        Integer passthroughMaxLines,

        @JsonProperty("passthrough_nonzero_exit")
        Boolean passthroughNonzeroExit
    ) {
        public Gate() {
            this(null, null, null);
        }

        public boolean passthroughOnNonzeroExit() {
            return Boolean.TRUE.equals(passthroughNonzeroExit);
        }
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
