package com.condense.filter.pipeline.config;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionSchemaRejectsUnknownKeysTest {

    @TempDir
    Path tempDir;

    @Test
    void unknownRootKeyIsRejectedWithPathAndLine() throws Exception {
        Path file = tempDir.resolve("filters.toml");
        Files.writeString(file, """
            schema_version = 1
            extra_root = true
            [filters."ls"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        FilterOverrideValidationResult result = new FilterOverrideLoader().validateFile(file, tempDir);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().getFirst()).contains("Unknown key");
        assertThat(result.errors().getFirst()).contains("extra_root");
        assertThat(result.errors().getFirst()).contains("line");
        assertThat(result.errors().getFirst()).doesNotContain("extra_root.extra_root");
    }

    @Test
    void unknownStageKeyIsRejectedWithPath() throws Exception {
        Path file = tempDir.resolve("filters.toml");
        Files.writeString(file, """
            schema_version = 1
            [filters."ls"]
            stages = [ { strategy = "ansi_strip", not_a_field = 1 } ]
            """);
        FilterOverrideValidationResult result = new FilterOverrideLoader().validateFile(file, tempDir);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().getFirst()).contains("Unknown key");
        assertThat(result.errors().getFirst()).contains("not_a_field");
    }

    @Test
    void wrongSchemaVersionIsRejected() throws Exception {
        Path file = tempDir.resolve("filters.toml");
        Files.writeString(file, """
            schema_version = 2
            [filters."ls"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        FilterOverrideValidationResult result = new FilterOverrideLoader().validateFile(file, tempDir);
        assertThat(result.status()).isEqualTo(FilterOverrideValidationResult.Status.SEMANTIC_ERROR);
        assertThat(result.errors().getFirst()).contains("schema_version");
        assertThat(result.errors().getFirst()).contains("2");
    }

    @Test
    void missingSchemaVersionIsRejected() throws Exception {
        Path file = tempDir.resolve("filters.toml");
        Files.writeString(file, """
            [filters."ls"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        FilterOverrideValidationResult result = new FilterOverrideLoader().validateFile(file, tempDir);
        assertThat(result.status()).isEqualTo(FilterOverrideValidationResult.Status.SEMANTIC_ERROR);
        assertThat(result.errors().getFirst()).contains("schema_version");
    }

    @Test
    void unknownInlineTestKeyIsRejected() {
        String toml = """
            schema_version = 1
            name = "x"
            commands = ["x"]
            stages = [ { strategy = "ansi_strip" } ]
            [[tests]]
            id = "t"
            input = ""
            expected = ""
            surprise = true
            """;
        assertThatThrownBy(() -> DefinitionMappers.STRICT_TOML.readValue(toml, BuiltinDefinition.class))
            .isInstanceOf(UnrecognizedPropertyException.class)
            .hasMessageContaining("surprise");
    }
}
