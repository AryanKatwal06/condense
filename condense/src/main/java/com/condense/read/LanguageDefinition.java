package com.condense.read;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;

/**
 * Schema v1 builtin language rule loaded from {@code classpath:languages/<name>.toml}.
 */
@RegisterForReflection
public record LanguageDefinition(
    @JsonProperty("schema_version")
    Integer schemaVersion,

    @JsonProperty("name")
    String name,

    @JsonProperty("extensions")
    List<String> extensions,

    @JsonProperty("filenames")
    List<String> filenames,

    @JsonProperty("family")
    String family,

    @JsonProperty("line_comment")
    String lineComment,

    @JsonProperty("block_comment_start")
    String blockCommentStart,

    @JsonProperty("block_comment_end")
    String blockCommentEnd,

    @JsonProperty("nest_block_comments")
    Boolean nestBlockComments,

    @JsonProperty("raw_strings")
    String rawStrings,

    @JsonProperty("strings")
    List<StringDef> strings,

    @JsonProperty("outline")
    List<OutlinePattern> outline,

    @JsonProperty("tests")
    List<InlineTest> tests
) {
    public static final int SCHEMA_VERSION = 1;

    public LanguageDefinition {
        if (extensions == null) {
            extensions = Collections.emptyList();
        }
        if (filenames == null) {
            filenames = Collections.emptyList();
        }
        if (strings == null) {
            strings = Collections.emptyList();
        }
        if (outline == null) {
            outline = Collections.emptyList();
        }
        if (tests == null) {
            tests = Collections.emptyList();
        }
    }

    public LanguageDefinition() {
        this(null, null, List.of(), List.of(), null, null, null, null, null, null, List.of(), List.of(), List.of());
    }

    public boolean allowsNestedBlockComments() {
        return Boolean.TRUE.equals(nestBlockComments);
    }

    @RegisterForReflection
    public record StringDef(
        @JsonProperty("delimiter")
        String delimiter,

        @JsonProperty("escape")
        String escape,

        @JsonProperty("raw")
        Boolean raw
    ) {
        public StringDef() {
            this(null, null, null);
        }

        public boolean rawString() {
            return Boolean.TRUE.equals(raw);
        }
    }

    @RegisterForReflection
    public record OutlinePattern(
        @JsonProperty("name")
        String name,

        @JsonProperty("regex")
        String regex
    ) {
        public OutlinePattern() {
            this(null, null);
        }
    }

    @RegisterForReflection
    public record InlineTest(
        @JsonProperty("id")
        String id,

        @JsonProperty("level")
        String level,

        @JsonProperty("input")
        String input,

        @JsonProperty("expected_contains")
        List<String> expectedContains,

        @JsonProperty("expected_absent")
        List<String> expectedAbsent
    ) {
        public InlineTest {
            if (expectedContains == null) {
                expectedContains = Collections.emptyList();
            }
            if (expectedAbsent == null) {
                expectedAbsent = Collections.emptyList();
            }
        }

        public InlineTest() {
            this(null, null, null, List.of(), List.of());
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
