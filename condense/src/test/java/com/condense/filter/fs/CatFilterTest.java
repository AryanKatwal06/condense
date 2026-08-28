package com.condense.filter.fs;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CatFilterTest extends FilterTestSupport {

    private final CatFilter filter = new CatFilter();
    private final CondenseConfig config = CondenseConfig.defaults();

    @Test
    void apply_smallOutput_returnsPassthrough() {
        String content = "Line 1\nLine 2\nLine 3\n";
        ExecutionResult result = success(content);

        FilterResult filterResult = filter.apply("cat file.txt", result, config, 0, false);
        assertPassthrough(filterResult);
        assertThat(filterResult.output()).isEqualTo(content);
    }

    @Test
    void apply_verboseLevelTwo_returnsPassthrough() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            sb.append("This is long line number ").append(i)
              .append(" containing detailed information to exceed threshold.\n");
        }
        String content = sb.toString();
        ExecutionResult result = success(content);

        FilterResult filterResult = filter.apply("cat file.txt", result, config, 2, false);
        assertPassthrough(filterResult);
    }

    @Test
    void apply_failedExecution_returnsPassthrough() {
        ExecutionResult result = failure(1, "cat: non-existent.txt: No such file or directory");

        FilterResult filterResult = filter.apply("cat non-existent.txt", result, config, 0, false);
        assertPassthrough(filterResult);
    }

    @Test
    void apply_largeText_compressesFirstAndLast20() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            sb.append("Data line ").append(i)
              .append(" with additional padding characters to ensure total file size exceeds two thousand characters limit.\n");
        }
        ExecutionResult result = success(sb.toString());

        FilterResult filterResult = filter.apply("cat large.txt", result, config, 0, false);
        assertCompressed(filterResult);
        assertThat(filterResult.output()).contains("Data line 1 ");
        assertThat(filterResult.output()).contains("Data line 20 ");
        assertThat(filterResult.output()).contains("... (20 lines omitted) ...");
        assertThat(filterResult.output()).contains("Data line 41 ");
        assertThat(filterResult.output()).contains("Data line 60 ");
    }

    @Test
    void apply_jsonOutput_returnsSkeleton() {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 1; i <= 50; i++) {
            sb.append("  {\"id\": ").append(i)
              .append(", \"name\": \"item_").append(i)
              .append("\", \"description\": \"Detailed item description string for testing JsonStructureStrategy\"}")
              .append(i < 50 ? ",\n" : "\n");
        }
        sb.append("]\n");
        ExecutionResult result = success(sb.toString());

        FilterResult filterResult = filter.apply("cat data.json", result, config, 0, false);
        assertCompressed(filterResult);
        assertThat(filterResult.output()).contains("\"... +49 more\"");
        assertThat(filterResult.output()).contains("\"<string>\"");
    }

    @Test
    void apply_unreadableStdoutFile_defaultsToPassthrough() throws IOException {
        Path tempStdout = Files.createTempFile("cat-test-deleted", ".tmp");
        Files.writeString(tempStdout, "Some content");
        Path tempStderr = Files.createTempFile("cat-test-err", ".tmp");

        // Delete stdout file to simulate an I/O error during Files.size()
        Files.delete(tempStdout);

        ExecutionResult result = new ExecutionResult(0, tempStdout, tempStderr, 10L);

        FilterResult filterResult = filter.apply("cat missing.txt", result, config, 0, false);
        assertPassthrough(filterResult);

        Files.deleteIfExists(tempStderr);
    }
}
