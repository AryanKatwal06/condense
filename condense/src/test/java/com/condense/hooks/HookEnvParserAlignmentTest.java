package com.condense.hooks;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the hook-side command analyzers against drifting from the Java
 * {@code CompoundCommandAnalyzer.ENV_ASSIGNMENT} regex. Every bundled Python
 * analyzer must use the strict {@code ^[A-Za-z_][A-Za-z0-9_]*=} environment
 * assignment test instead of the loose {@code "=" in token} heuristic.
 */
class HookEnvParserAlignmentTest {

    private static final List<String> ANALYZER_RESOURCES = List.of(
        "/hooks/claude-code/condense-hook.sh",
        "/hooks/cursor/condense-hook.sh",
        "/hooks/windsurf/condense-hook.sh",
        "/hooks/copilot/condense-hook.sh",
        "/hooks/codex/condense-hook.sh",
        "/hooks/kilo/condense-hook.sh",
        "/hooks/antigravity/condense-hook.sh",
        "/hooks/cline/PreToolUse",
        "/hooks/hermes/__init__.py"
    );

    private static final String JAVA_REGEX = "[A-Za-z_][A-Za-z0-9_]*=";

    @Test
    void everyPythonAnalyzerUsesTheStrictEnvAssignmentPattern() throws Exception {
        for (String resource : ANALYZER_RESOURCES) {
            String script = read(resource);

            assertThat(script)
                .as("%s must define the shared env-assignment helper", resource)
                .contains("def is_env_assignment(token):")
                .contains("is_env_assignment(w)")
                .contains("is_env_assignment(opt)")
                .contains(JAVA_REGEX);

            assertThat(script)
                .as("%s must not fall back to the loose = heuristic", resource)
                .doesNotContain("if \"=\" in w and not w.startswith(\"=\"):")
                .doesNotContain("elif \"=\" in opt and not opt.startswith(\"=\"):");
        }
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = HookEnvParserAlignmentTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("resource %s must exist", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
