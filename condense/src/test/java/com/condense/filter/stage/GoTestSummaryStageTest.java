package com.condense.filter.stage;

import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GoTestSummaryStageTest {

    private final GoTestSummaryStage stage = GoTestSummaryStage.INSTANCE;

    @Test
    void jsonPassingAllTestsReportedCleanly() {
        String jsonInput = """
            {"Action":"run","Package":"example.com/math","Test":"TestAdd"}
            {"Action":"output","Package":"example.com/math","Test":"TestAdd","Output":"=== RUN   TestAdd\\n"}
            {"Action":"pass","Package":"example.com/math","Test":"TestAdd","Elapsed":0.001}
            {"Action":"run","Package":"example.com/math","Test":"TestSubtract"}
            {"Action":"output","Package":"example.com/math","Test":"TestSubtract","Output":"=== RUN   TestSubtract\\n"}
            {"Action":"pass","Package":"example.com/math","Test":"TestSubtract","Elapsed":0.002}
            {"Action":"pass","Package":"example.com/math","Elapsed":0.003}
            """;

        StageResult result = stage.process(jsonInput, FilterContext.empty());
        assertThat(result.output()).isEqualTo("passed: 2");
    }

    @Test
    void jsonFailingPreservesAssertionAndDiagnosticLines() {
        String jsonInput = """
            {"Action":"run","Package":"example.com/calc","Test":"TestMultiply"}
            {"Action":"output","Package":"example.com/calc","Test":"TestMultiply","Output":"=== RUN   TestMultiply\\n"}
            {"Action":"output","Package":"example.com/calc","Test":"TestMultiply","Output":"    calc_test.go:12: expected 4, got 5\\n"}
            {"Action":"output","Package":"example.com/calc","Test":"TestMultiply","Output":"    calc_test.go:13: difference was +1\\n"}
            {"Action":"output","Package":"example.com/calc","Test":"TestMultiply","Output":"--- FAIL: TestMultiply (0.01s)\\n"}
            {"Action":"fail","Package":"example.com/calc","Test":"TestMultiply","Elapsed":0.01}
            {"Action":"run","Package":"example.com/calc","Test":"TestDivide"}
            {"Action":"output","Package":"example.com/calc","Test":"TestDivide","Output":"=== RUN   TestDivide\\n"}
            {"Action":"output","Package":"example.com/calc","Test":"TestDivide","Output":"    calc_test.go:20: division by zero\\n"}
            {"Action":"output","Package":"example.com/calc","Test":"TestDivide","Output":"--- FAIL: TestDivide (0.00s)\\n"}
            {"Action":"fail","Package":"example.com/calc","Test":"TestDivide","Elapsed":0.00}
            {"Action":"fail","Package":"example.com/calc","Elapsed":0.02}
            """;

        StageResult result = stage.process(jsonInput, FilterContext.empty());
        assertThat(result.output()).isEqualTo("""
            go test: 2 failure(s)
              FAIL: TestMultiply
                calc_test.go:12: expected 4, got 5
                calc_test.go:13: difference was +1
              FAIL: TestDivide
                calc_test.go:20: division by zero
            passed: 0 | failed: 2""");
    }

    @Test
    void jsonTypicalFixtureByteCompatible() throws IOException {
        String input = loadResource("/fixtures/go-test/json-typical.txt");
        StageResult result = stage.process(input, FilterContext.empty());

        assertThat(result.output()).isEqualTo("""
            go test: 2 failure(s)
              FAIL: TestMultiply
              FAIL: TestDivide
            passed: 1 | failed: 2""");
    }

    @Test
    void jsonPassingFixtureByteCompatible() throws IOException {
        String input = loadResource("/fixtures/go-test/json-passing.txt");
        StageResult result = stage.process(input, FilterContext.empty());

        assertThat(result.output()).isEqualTo("passed: 2");
    }

    @Test
    void jsonPackageBuildFailurePreservesDiagnostics() {
        String jsonInput = """
            {"Action":"output","Package":"example.com/broken","Output":"# example.com/broken\\n"}
            {"Action":"output","Package":"example.com/broken","Output":"./calc.go:5:2: syntax error: unexpected newline\\n"}
            {"Action":"output","Package":"example.com/broken","Output":"FAIL\\texample.com/broken [build failed]\\n"}
            {"Action":"fail","Package":"example.com/broken","Elapsed":0.05}
            """;

        StageResult result = stage.process(jsonInput, FilterContext.empty());
        assertThat(result.output()).contains("go test: build failed in example.com/broken");
        assertThat(result.output()).contains("./calc.go:5:2: syntax error: unexpected newline");
    }

    @Test
    void plainTextPassingPreserved() {
        String plain = "ok  \tpackage/foo\t0.01s\n";
        StageResult result = stage.process(plain, FilterContext.empty());
        assertThat(result.output()).isEqualTo(plain);
    }

    @Test
    void plainTextFailingPreservesAssertionDetails() {
        String plain = """
            === RUN   TestAdd
            --- PASS: TestAdd (0.00s)
            === RUN   TestSubtract
                sub_test.go:15: expected 1, got -1
            --- FAIL: TestSubtract (0.00s)
            FAIL
            """;

        StageResult result = stage.process(plain, FilterContext.empty());
        assertThat(result.output()).isEqualTo("""
            go test: 1 failure(s)
              FAIL: TestSubtract
                sub_test.go:15: expected 1, got -1""");
    }

    @Test
    void plainTextFallbackMatchesLegacyFixture() {
        String plain = "--- FAIL: TestFallback\nFAIL";
        StageResult result = stage.process(plain, FilterContext.empty());
        assertThat(result.output()).contains("FAIL: TestFallback");
    }

    @Test
    void plainTextMultipleFailuresCompressed() {
        String plain = """
            running 3 tests
            test tests::test_add ... ok
            --- FAIL: tests::test_multiply (0.00s)
            --- FAIL: tests::test_divide (0.00s)
            FAILED
            """;

        StageResult result = stage.process(plain, FilterContext.empty());
        assertThat(result.output()).isEqualTo("""
            go test: 2 failure(s)
              FAIL: tests::test_multiply
              FAIL: tests::test_divide""");
    }

    @Test
    void failOpenOnNonZeroExitCodeWithUnknownFormat() {
        String unknownError = "go test: error: could not locate go.mod in root\n";
        FilterContext ctx = new FilterContext("go test ./...", new ExecutionResult(1, unknownError, "", 100L), com.condense.core.CondenseConfig.defaults(), 0, false);
        StageResult result = stage.process(unknownError, ctx);
        assertThat(result.output()).isEqualTo(unknownError);
    }

    @Test
    void handlesEmptyOrMalformedInputGracefully() {
        assertThat(stage.process("", FilterContext.empty()).output()).isEmpty();
        assertThat(stage.process(null, FilterContext.empty()).output()).isEmpty();
        assertThat(stage.process("   \n\t  ", FilterContext.empty()).output()).isEqualTo("   \n\t  ");

        String malformedJson = "{not json}\n{\"Action\":}\n";
        StageResult result = stage.process(malformedJson, FilterContext.empty());
        assertThat(result.output()).isEqualTo(malformedJson);
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream in = GoTestSummaryStageTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
