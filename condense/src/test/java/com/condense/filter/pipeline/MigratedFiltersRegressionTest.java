package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.FilterTestSupport;
import com.condense.filter.fs.LsFilter;
import com.condense.filter.node.ESLintFilter;
import com.condense.filter.node.NpmInstallFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigratedFiltersRegressionTest extends FilterTestSupport {

    private NpmInstallFilter npmFilter;
    private LsFilter lsFilter;
    private ESLintFilter eslintFilter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() {
        npmFilter = new NpmInstallFilter();
        lsFilter = new LsFilter();
        eslintFilter = new ESLintFilter();
        config = CondenseConfig.defaults();
    }

    // --- NpmInstallFilter Regression Tests ---

    @Test
    @DisplayName("NpmInstallFilter produces expected output for typical install")
    void npmInstall_typicalOutput() throws Exception {
        String typical = fixture("npm-install", "typical");
        FilterResult result = npmFilter.apply("npm install", success(typical), config, 0, false);

        assertThat(result.output()).startsWith("condense[filtered]");
        assertThat(result.output()).contains("✓ npm install");
        assertThat(result.output()).contains("packages");
        assertCompressed(result);
    }

    @Test
    @DisplayName("NpmInstallFilter produces expected output for install with vulnerabilities")
    void npmInstall_withVulnsOutput() throws Exception {
        String withVulns = fixture("npm-install", "with-vulns");
        FilterResult result = npmFilter.apply("npm install", success(withVulns), config, 0, false);

        assertThat(result.output()).startsWith("condense[filtered]");
        assertThat(result.output()).contains("✓ npm install");
        assertThat(result.output()).contains("vulnerabilit");
        assertCompressed(result);
    }

    @Test
    @DisplayName("NpmInstallFilter passes through on command failure")
    void npmInstall_failurePassthrough() {
        ExecutionResult failed = failure(1, "npm ERR! 404 Not Found");
        FilterResult result = npmFilter.apply("npm install", failed, config, 0, false);

        assertThat(result.wasFiltered()).isFalse();
        assertThat(result.output()).contains("404 Not Found");
    }

    // --- LsFilter Regression Tests ---

    @Test
    @DisplayName("LsFilter produces (empty directory) for empty list")
    void lsFilter_emptyDirectory() {
        FilterResult result = lsFilter.apply("ls", success(""), config, 0, false);
        assertThat(result.output()).isEqualTo("condense[filtered]\n(empty directory)");
    }

    @Test
    @DisplayName("LsFilter passes through small directories (<=10 items)")
    void lsFilter_smallDirectoryPassthrough() {
        String smallList = "file1.txt\nfile2.txt\nfile3.txt";
        FilterResult result = lsFilter.apply("ls", success(smallList), config, 0, false);
        assertPassthrough(result);
        assertThat(result.output()).isEqualTo(smallList);
    }

    @Test
    @DisplayName("LsFilter compresses large directory (>10 items) into tree summary")
    void lsFilter_largeDirectoryTree() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("src/com/pkg/File").append(i).append(".java\n");
        }
        FilterResult result = lsFilter.apply("ls", success(sb.toString()), config, 0, false);

        assertCompressed(result);
        assertThat(result.output()).contains("src/");
    }

    // --- ESLintFilter Regression Tests ---

    @Test
    @DisplayName("ESLintFilter parses human-readable text failure output")
    void eslintFilter_textFailureOutput() throws Exception {
        String typical = fixture("eslint", "typical");
        ExecutionResult exec = new ExecutionResult(1, typical, "", 200L);
        FilterResult result = eslintFilter.apply("eslint", exec, config, 0, false);

        assertThat(result.output()).contains("eslint:");
        assertThat(result.output()).contains("error(s)");
        assertCompressed(result);
    }

    @Test
    @DisplayName("ESLintFilter handles passing text output")
    void eslintFilter_passingTextOutput() throws Exception {
        String passing = fixture("eslint", "passing");
        FilterResult result = eslintFilter.apply("eslint", success(passing), config, 0, false);

        assertThat(result.output()).isEqualTo("condense[filtered]\n✓ no lint issues");
    }

    @Test
    @DisplayName("ESLintFilter parses JSON formatted lint output")
    void eslintFilter_jsonOutput() {
        String json = """
            [
              {
                "filePath": "/app/index.js",
                "messages": [
                  { "ruleId": "no-unused-vars", "severity": 2, "message": "'x' is defined but never used." },
                  { "ruleId": "semi", "severity": 1, "message": "Missing semicolon." }
                ],
                "errorCount": 1,
                "warningCount": 1
              }
            ]
            """;
        ExecutionResult exec = new ExecutionResult(1, json, "", 100L);
        FilterResult result = eslintFilter.apply("eslint --format json", exec, config, 0, false);

        assertThat(result.output()).contains("eslint: 1 error(s), 1 warning(s)");
        assertThat(result.output()).contains("no-unused-vars: 1");
        assertThat(result.output()).contains("semi: 1");
        assertCompressed(result);
    }

    @Test
    @DisplayName("ESLintFilter handles clean JSON output")
    void eslintFilter_cleanJsonOutput() {
        String json = """
            [
              {
                "filePath": "/app/index.js",
                "messages": [],
                "errorCount": 0,
                "warningCount": 0
              }
            ]
            """;
        ExecutionResult exec = new ExecutionResult(0, json, "", 50L);
        FilterResult result = eslintFilter.apply("eslint --format json", exec, config, 0, false);

        assertThat(result.output()).isEqualTo("condense[filtered]\n✓ no lint issues");
    }
}
