package com.condense.filter.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FidelityGuardTest {

    private final FidelityGuard guard = FidelityGuard.INSTANCE;

    private static final String PATCH = """
        diff --git a/src/main.rs b/src/main.rs
        index 8311ab3..70a0b23 100644
        --- a/src/main.rs
        +++ b/src/main.rs
        @@ -1,2 +1,2 @@
        -old
        +new
        """;

    @Test
    void preservedPatchPasses() {
        String filtered = PATCH.replace("index 8311ab3..70a0b23 100644\n", "");

        FidelityGuard.Verdict verdict = guard.check("git diff", 0, PATCH, filtered);

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.failures()).isEmpty();
    }

    @Test
    void collapsedPatchFails() {
        FidelityGuard.Verdict verdict = guard.check("git diff", 0, PATCH, "+1 / -1 lines");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failures()).anyMatch(f -> f.contains("unified diff"));
        assertThat(verdict.detail()).startsWith(FidelityGuard.INCIDENT_DETAIL_PREFIX);
    }

    @Test
    void patchWithoutChangeLinesFails() {
        String filtered = """
            diff --git a/src/main.rs b/src/main.rs
            --- a/src/main.rs
            +++ b/src/main.rs
            @@ -1,2 +1,2 @@
            """;

        FidelityGuard.Verdict verdict = guard.check("git diff", 0, PATCH, filtered);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failures()).contains("unified diff change lines dropped");
    }

    @Test
    void droppedStackTraceFailsOnNonZeroExit() {
        String raw = """
            Traceback (most recent call last):
              File "app.py", line 4, in <module>
                raise ValueError("boom")
            ValueError: boom
            """;

        FidelityGuard.Verdict verdict = guard.check("python app.py", 1, raw, "ValueError: boom");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failures()).anyMatch(f -> f.contains("exception marker dropped"));
    }

    @Test
    void keptStackTracePassesOnNonZeroExit() {
        String raw = """
            Traceback (most recent call last):
              File "app.py", line 4, in <module>
                raise ValueError("boom")
            ValueError: boom
            """;
        String filtered = """
            condense[filtered]
            Traceback (most recent call last):
              File "app.py", line 4, in <module>
            ValueError: boom
            """;

        FidelityGuard.Verdict verdict = guard.check("python app.py", 1, raw, filtered);

        assertThat(verdict.passed()).isTrue();
    }

    @Test
    void droppedCompilerLocationFails() {
        String raw = """
            src/main.rs:12:5: error[E0425]: cannot find value `x` in this scope
            error: could not compile `app`
            """;

        FidelityGuard.Verdict verdict = guard.check("cargo build", 101, raw, "error: could not compile `app`");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failures()).contains("compiler diagnostic dropped src/main.rs:12");
    }

    @Test
    void keptCompilerLocationPasses() {
        String raw = "src/main.rs:12:5: error[E0425]: cannot find value `x` in this scope\n";

        FidelityGuard.Verdict verdict = guard.check("cargo build", 101, raw, raw);

        assertThat(verdict.passed()).isTrue();
    }

    @Test
    void pythonLinterLocationsAreNotCompilerDiagnostics() {
        String raw = "src/app.py:1: error: incompatible type\n";

        FidelityGuard.Verdict verdict = guard.check("mypy src", 1, raw, "1 issue in 1 file\n");

        assertThat(verdict.passed()).isTrue();
    }

    @Test
    void droppedTestFailuresFail() {
        String raw = """
            running 2 tests
            test ok ... ok
            FAILED test_broken
            AssertionError: expected 1 but was 2
            """;

        FidelityGuard.Verdict verdict = guard.check("cargo test", 101, raw, "2 tests, 1 issue");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failures()).contains("test failure markers dropped");
    }

    @Test
    void summaryCommandSkipsStrictFailureChecks() {
        String raw = "Traceback (most recent call last):\n  boom\n";
        String filtered = "1 file changed, 1 insertion(+)\n";

        FidelityGuard.Verdict verdict = guard.check("git status", 1, raw, filtered);

        assertThat(verdict.passed()).isTrue();
    }

    @Test
    void emptyFilteredOutputFails() {
        FidelityGuard.Verdict verdict = guard.check("npm install", 0, "added 3 packages\n", "");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failures()).contains("filtered output is empty");
    }

    @Test
    void unchangedOutputPasses() {
        String raw = "nothing to see\n";

        FidelityGuard.Verdict verdict = guard.check("git diff", 0, raw, raw);

        assertThat(verdict.passed()).isTrue();
    }
}
