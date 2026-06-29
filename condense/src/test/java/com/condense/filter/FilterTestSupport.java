package com.condense.filter;

import com.condense.core.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class providing fixture loading and assertion helpers for filter tests.
 *
 * <p>Fixture files live under {@code src/test/resources/fixtures/{command-name}/}.
 * Each fixture directory must contain at least {@code typical.txt}.
 */
public abstract class FilterTestSupport {

    /** Loads a fixture file from the classpath. */
    protected String fixture(String commandDir, String fixtureName) throws Exception {
        String path = "/fixtures/" + commandDir + "/" + fixtureName + ".txt";
        URL url = getClass().getResource(path);
        assertThat(url)
            .as("Fixture '%s' not found on classpath", path)
            .isNotNull();
        return Files.readString(Path.of(url.toURI()));
    }

    /** Creates an ExecutionResult with exit 0 and the given stdout. */
    protected ExecutionResult success(String stdout) {
        return new ExecutionResult(0, stdout, "", 10L);
    }

    /** Creates an ExecutionResult with non-zero exit and stderr. */
    protected ExecutionResult failure(int exitCode, String stderr) {
        return new ExecutionResult(exitCode, "", stderr, 5L);
    }

    /** Creates an ExecutionResult with non-zero exit and both streams. */
    protected ExecutionResult failure(int exitCode, String stdout, String stderr) {
        return new ExecutionResult(exitCode, stdout, stderr, 5L);
    }

    /** Asserts that the filter output is shorter than the raw input in tokens. */
    protected void assertCompressed(FilterResult result) {
        assertThat(result.rawTokens())
            .as("Expected rawTokens > outTokens (filter should compress)")
            .isGreaterThan(result.outTokens());
        assertThat(result.wasFiltered()).isTrue();
    }

    /** Asserts that the filter did not modify the output. */
    protected void assertPassthrough(FilterResult result) {
        assertThat(result.wasFiltered()).isFalse();
        assertThat(result.rawTokens()).isEqualTo(result.outTokens());
    }
}