package com.condense.filter.fs;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LsFilterTest extends FilterTestSupport {

    private LsFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() {
        filter = new LsFilter();
        config = CondenseConfig.defaults();
    }

    @Test
    void emptyDirectory_returnsEmptyDirectoryIndicator() {
        FilterResult r = filter.apply("ls", success(""), config, 0, false);
        assertThat(r.output()).isEqualTo("(empty directory)");
    }

    @Test
    void smallDirectory_passesThroughUnmodified() {
        String files = "file1.txt\nfile2.txt\nfile3.txt";
        FilterResult r = filter.apply("ls", success(files), config, 0, false);
        assertThat(r.output()).isEqualTo(files);
        assertPassthrough(r);
    }

    @Test
    void largeDirectory_compressesToTreeSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("src/main/com/example/File").append(i).append(".java\n");
        }
        FilterResult r = filter.apply("ls", success(sb.toString()), config, 0, false);
        assertThat(r.output()).contains("src/");
        assertCompressed(r);
    }

    @Test
    void verboseMode_passesThroughEvenWhenLarge() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("file").append(i).append(".txt\n");
        }
        FilterResult r = filter.apply("ls", success(sb.toString()), config, 2, false);
        assertPassthrough(r);
    }

    @Test
    void failure_passesThrough() {
        FilterResult r = filter.apply("ls", failure(1, "ls: cannot open directory: Permission denied"), config, 0, false);
        assertThat(r.output()).contains("Permission denied");
        assertThat(r.wasFiltered()).isFalse();
    }
}
