package com.condense.filter.git;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class GitDiffFilterTest extends FilterTestSupport {

    private GitDiffFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new GitDiffFilter(); config = CondenseConfig.defaults(); }

    @Test
    void statSummary_extractsSummaryLine() throws Exception {
        FilterResult r = filter.apply("git diff",
            success(fixture("git-diff", "stat")), config, 0, false);
        assertThat(r.output()).contains("files changed");
        assertCompressed(r);
    }

    @Test
    void patchDiff_preservesFileHeadersHunksAndChanges() throws Exception {
        FilterResult r = filter.apply("git diff",
            success(fixture("git-diff", "typical")), config, 0, false);

        assertThat(r.output())
            .contains("diff --git a/src/main.rs b/src/main.rs")
            .contains("--- a/src/main.rs")
            .contains("+++ b/src/main.rs")
            .contains("@@ -1,3 +1,4 @@")
            .contains("+use std::env;")
            .contains("-    println!(\"Hello, world!\");")
            .contains("+    println!(\"Hello, {}!\", env::var(\"USER\").unwrap());")
            .doesNotContain("index 8311ab3");
        assertCompressed(r);
    }

    @Test
    void multiFilePatch_keepsEveryFileSection() {
        String raw = """
            diff --git a/a.py b/a.py
            index 1111111..2222222 100644
            --- a/a.py
            +++ b/a.py
            @@ -1 +1 @@
            -print(1)
            +print(2)
            diff --git a/b.py b/b.py
            index 3333333..4444444 100644
            --- a/b.py
            +++ b/b.py
            @@ -1 +1 @@
            -print(3)
            +print(4)
            """;

        FilterResult r = filter.apply("git diff", success(raw), config, 0, false);

        assertThat(r.output())
            .contains("diff --git a/a.py b/a.py")
            .contains("diff --git a/b.py b/b.py")
            .contains("+print(2)")
            .contains("+print(4)")
            .doesNotContain("index 1111111")
            .doesNotContain("index 3333333");
    }

    @Test
    void renameAndBinary_keepGitMetadata() {
        String raw = """
            diff --git a/old.txt b/new.txt
            similarity index 100%
            rename from old.txt
            rename to new.txt
            diff --git a/logo.png b/logo.png
            index 5555555..6666666 100644
            Binary files a/logo.png and b/logo.png differ
            """;

        FilterResult r = filter.apply("git diff", success(raw), config, 0, false);

        assertThat(r.output())
            .contains("rename from old.txt")
            .contains("rename to new.txt")
            .contains("Binary files a/logo.png and b/logo.png differ")
            .doesNotContain("index 5555555");
    }

    @Test
    void oversizedHunk_keepsHeadAndTailWithOmissionMarker() {
        String body = IntStream.range(0, 200)
            .mapToObj(i -> "+line" + i)
            .collect(Collectors.joining("\n"));
        String raw = "diff --git a/big.rs b/big.rs\n"
            + "index 7777777..8888888 100644\n"
            + "--- a/big.rs\n"
            + "+++ b/big.rs\n"
            + "@@ -1,1 +1,200 @@\n"
            + body
            + "\n";

        FilterResult r = filter.apply("git diff", success(raw), config, 0, false);

        assertThat(r.output())
            .contains("@@ -1,1 +1,200 @@")
            .contains("+line0")
            .contains("+line39")
            .contains("+line180")
            .contains("+line199")
            .contains("hunk lines omitted")
            .doesNotContain("+line100");
    }

    @Test
    void manyHunks_truncateAtTotalBudget() {
        StringBuilder raw = new StringBuilder();
        for (int hunk = 0; hunk < 20; hunk++) {
            raw.append("diff --git a/f").append(hunk).append(".rs b/f").append(hunk).append(".rs\n");
            raw.append("--- a/f").append(hunk).append(".rs\n");
            raw.append("+++ b/f").append(hunk).append(".rs\n");
            raw.append("@@ -1,1 +1,60 @@\n");
            for (int line = 0; line < 60; line++) {
                raw.append("+f").append(hunk).append("-line").append(line).append('\n');
            }
        }

        FilterResult r = filter.apply("git diff", success(raw.toString()), config, 0, false);

        assertThat(r.output())
            .contains("diff lines omitted")
            .contains("diff --git a/f0.rs b/f0.rs");
        assertThat(r.output().lines().count()).isLessThanOrEqualTo(362);
    }

    @Test
    void statFlagWithPatch_stillKeepsThePatch() {
        String raw = """
            diff --git a/src/main.rs b/src/main.rs
            index 8311ab3..70a0b23 100644
            --- a/src/main.rs
            +++ b/src/main.rs
            @@ -1,2 +1,2 @@
            -old
            +new
            """;

        FilterResult r = filter.apply("git diff --stat -p", success(raw), config, 0, false);

        assertThat(r.output())
            .contains("@@ -1,2 +1,2 @@")
            .contains("+new");
    }

    @Test
    void numstat_preservesEveryRow() {
        String raw = "2\t1\tsrc/main.rs\n1\t0\tdocs/readme.md\n";

        FilterResult r = filter.apply("git diff --numstat", success(raw), config, 0, false);

        assertThat(r.output())
            .contains("2\t1\tsrc/main.rs")
            .contains("1\t0\tdocs/readme.md");
    }

    @Test
    void nonZeroExit_passesThrough() {
        FilterResult r = filter.apply("git diff",
            failure(1, "fatal: bad revision"), config, 0, false);
        assertPassthrough(r);
        assertThat(r.output()).contains("fatal:");
    }
}
