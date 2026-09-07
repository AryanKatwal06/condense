package com.condense.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HookTemplateAnalyzerTest {

    @Test
    void allHookTemplatesContainFiniteStateAnalyzerOrWrapperPolicy() throws IOException {
        for (HookTool tool : HookTool.values()) {
            String template = HookTemplate.load(tool);
            assertThat(template).as("Template for %s", tool.displayName).isNotBlank();
            assertThat(HookTemplate.isManagedByCondense(template))
                .as("Template for %s has Condense sentinel", tool.displayName)
                .isTrue();

            if (tool == HookTool.GENERIC_BASH) {
                assertThat(template).contains("exec condense \"$@\"");
                assertThat(template).contains("Execution-Wrapper Policy");
            } else if (tool == HookTool.HERMES) {
                assertThat(template).contains("entry: __init__.py");
                try (InputStream in = getClass().getResourceAsStream("/hooks/hermes/__init__.py")) {
                    assertThat(in).isNotNull();
                    String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(script).contains("analyze_command");
                    assertThat(script).contains("cancel");
                }
            } else {
                assertThat(template).satisfiesAnyOf(
                    t -> assertThat(t).contains("analyze_command"),
                    t -> assertThat(t).contains("analyzeCommand")
                );
                assertThat(template).contains("deny");
                assertThat(template).satisfiesAnyOf(
                    t -> assertThat(t).contains("permissionDecision"),
                    t -> assertThat(t).contains("permission"),
                    t -> assertThat(t).contains("decision"),
                    t -> assertThat(t).contains("cancel"),
                    t -> assertThat(t).contains("errorMessage"),
                    t -> assertThat(t).contains("sys.exit(2)")
                );
            }
        }
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void pythonTemplateInterceptionEndToEnd() throws Exception {
        String pyTemplate;
        try (InputStream in = getClass().getResourceAsStream("/hooks/hermes/__init__.py")) {
            assertThat(in).isNotNull();
            pyTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String applied = pyTemplate.replace(HookCommands.PLACEHOLDER, HookCommands.spaceSeparated(null));

        Path scriptFile = Files.createTempFile("condense-test-py-hook", ".py");
        try {
            Files.writeString(scriptFile, applied, StandardCharsets.UTF_8);

            // Test 1: Compound command with git status should deny
            String out1 = runPythonHook(scriptFile, "{\"command\": \"echo ok && git status\"}");
            assertThat(out1).contains("\"cancel\": true");

            // Test 2: Unrelated command (neither whoami nor uname is in builtin catalog) should not deny
            String out2 = runPythonHook(scriptFile, "{\"command\": \"whoami && uname -a\"}");
            assertThat(out2).doesNotContain("\"cancel\": true");

            // Test 3: Ambiguous eval syntax should deny
            String out3 = runPythonHook(scriptFile, "{\"command\": \"eval \\\"something\\\"\"}");
            assertThat(out3).contains("\"cancel\": true");

            // Test 4: Environment variable prefix before git diff should deny
            String out4 = runPythonHook(scriptFile, "{\"command\": \"GIT_PAGER=cat git diff\"}");
            assertThat(out4).contains("\"cancel\": true");

            // Test 5: Redirection before git log should deny
            String out5 = runPythonHook(scriptFile, "{\"command\": \"< file.txt git log\"}");
            assertThat(out5).contains("\"cancel\": true");

            // Test 6: Unclosed quote should deny
            String out6 = runPythonHook(scriptFile, "{\"command\": \"echo hello && git diff \\\"\"}");
            assertThat(out6).contains("\"cancel\": true");
        } finally {
            Files.deleteIfExists(scriptFile);
        }
    }

    @Test
    @EnabledIf("isNodeAvailable")
    void nodeTemplateInterceptionEndToEnd() throws Exception {
        String template = HookTemplate.load(HookTool.OPENCODE);
        String applied = HookTemplate.apply(HookTool.OPENCODE, template, List.of());

        Path scriptFile = Files.createTempFile("condense-test-js-hook", ".js");
        try {
            Files.writeString(scriptFile, applied, StandardCharsets.UTF_8);

            // Test 1: Compound command with git status should deny
            String out1 = runNodeHook(scriptFile, "{\"command\": \"echo ok && git status\"}");
            assertThat(out1).contains("\"permissionDecision\":\"deny\"");

            // Test 2: Unrelated command (neither whoami nor uname is in builtin catalog) should not deny
            String out2 = runNodeHook(scriptFile, "{\"command\": \"whoami && uname -a\"}");
            assertThat(out2).doesNotContain("\"deny\"");

            // Test 3: Ambiguous eval syntax should deny
            String out3 = runNodeHook(scriptFile, "{\"command\": \"eval \\\"something\\\"\"}");
            assertThat(out3).contains("\"permissionDecision\":\"deny\"");

            // Test 4: Environment variable prefix before git diff should deny
            String out4 = runNodeHook(scriptFile, "{\"command\": \"GIT_PAGER=cat git diff\"}");
            assertThat(out4).contains("\"permissionDecision\":\"deny\"");

            // Test 5: Redirection before git log should deny
            String out5 = runNodeHook(scriptFile, "{\"command\": \"< file.txt git log\"}");
            assertThat(out5).contains("\"permissionDecision\":\"deny\"");

            // Test 6: Unclosed quote should deny
            String out6 = runNodeHook(scriptFile, "{\"command\": \"echo hello && git diff \\\"\"}");
            assertThat(out6).contains("\"permissionDecision\":\"deny\"");
        } finally {
            Files.deleteIfExists(scriptFile);
        }
    }

    private String runPythonHook(Path scriptPath, String inputJson) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python", scriptPath.toAbsolutePath().toString());
        return runSubprocess(pb, inputJson);
    }

    private String runNodeHook(Path scriptPath, String inputJson) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("node", scriptPath.toAbsolutePath().toString());
        return runSubprocess(pb, inputJson);
    }

    private String runSubprocess(ProcessBuilder pb, String inputJson) throws Exception {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.getOutputStream().write(inputJson.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        process.getOutputStream().close();
        byte[] output = process.getInputStream().readAllBytes();
        process.waitFor();
        return new String(output, StandardCharsets.UTF_8);
    }

    static boolean isPythonAvailable() {
        try {
            Process p = new ProcessBuilder("python", "--version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isNodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "-v").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
