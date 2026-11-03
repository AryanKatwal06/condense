package com.condense.filter.fs;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SearchFilterTest extends FilterTestSupport {

    private final GrepFilter grep = new GrepFilter();
    private final FindFilter find = new FindFilter();
    private final CondenseConfig config = CondenseConfig.defaults();

    private static String grepLines(int count) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(i -> "src/app.js:" + i + ": error foo" + i)
            .collect(Collectors.joining("\n")) + "\n";
    }

    private static String findPaths(int count) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(i -> "src/main/java/com/example/File" + i + ".java")
            .collect(Collectors.joining("\n")) + "\n";
    }

    @Test
    void grepSmallResultKeepsMatchedLines() {
        String content = grepLines(12);

        FilterResult result = grep.apply("grep foo", success(content), config, 0, false);

        assertPassthrough(result);
        assertThat(result.output()).isEqualTo(content);
        assertThat(result.output()).contains("src/app.js:12: error foo12");
    }

    @Test
    void grepLargeResultKeepsHeaderAndBoundedWindow() {
        String content = grepLines(100);

        FilterResult result = grep.apply("grep foo", success(content), config, 0, false);

        assertCompressed(result);
        assertThat(result.output())
            .contains("100 match(es) in 1 file(s)")
            .contains("src/app.js:1: error foo1")
            .contains("... (20 lines omitted: lines 61..80 of 100) ...")
            .contains("src/app.js:100: error foo100")
            .contains("src/app.js:81: error foo81");
    }

    @Test
    void grepExitOneIsNoMatches() {
        FilterResult result = grep.apply("grep nomatch", failure(1, ""), config, 0, false);

        assertThat(result.output()).endsWith("(no matches)");
    }

    @Test
    void findSmallResultKeepsPaths() {
        String content = findPaths(12);

        FilterResult result = find.apply("find .", success(content), config, 0, false);

        assertPassthrough(result);
        assertThat(result.output()).isEqualTo(content);
        assertThat(result.output()).contains("File12.java");
    }

    @Test
    void findLargeResultKeepsHeaderAndBoundedWindow() {
        String content = findPaths(100);

        FilterResult result = find.apply("find .", success(content), config, 0, false);

        assertCompressed(result);
        assertThat(result.output())
            .contains("100 result(s)")
            .contains("src/main/java/com/example/File1.java")
            .contains("... (20 lines omitted: lines 61..80 of 100) ...")
            .contains("src/main/java/com/example/File100.java");
    }

    @Test
    void findEmptyResultIsNoResults() {
        FilterResult result = find.apply("find . -name nope", success(""), config, 0, false);

        assertThat(result.output()).endsWith("(no results)");
    }
}
