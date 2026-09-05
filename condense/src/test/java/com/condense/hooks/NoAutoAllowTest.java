package com.condense.hooks;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class NoAutoAllowTest {

    @Test
    void noTemplateRewritesACommandAndAllowsIt() throws Exception {
        for (HookTool tool : HookTool.values()) {
            String content = HookTemplate.load(tool);
            assertNoRewriteAndAllow(tool.templateResource, content);
        }
        for (String extra : new String[] {
            "/hooks/hermes/__init__.py",
            "/hooks/copilot/condense-hook.ps1",
            "/hooks/windsurf/condense-hook.ps1"
        }) {
            try (var in = NoAutoAllowTest.class.getResourceAsStream(extra)) {
                assertThat(in).as(extra).isNotNull();
                assertNoRewriteAndAllow(extra, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    private static void assertNoRewriteAndAllow(String name, String content) {
        assertThat(content)
            .as("%s must not emit updatedInput", name)
            .doesNotContain("updatedInput")
            .doesNotContain("updated_input");
        boolean rewritesCommand = content.contains("\"condense \" + command")
            && (content.contains("tool_input") || content.contains("updated"));
        boolean allows = content.contains("\"permissionDecision\": \"allow\"")
            || content.contains("\"permissionDecision\":\"allow\"");
        assertThat(rewritesCommand && allows)
            .as("%s must not auto-allow a rewritten command", name)
            .isFalse();
    }

    @Test
    void claudeDenyMentionsCondense() throws IOException {
        String content = HookTemplate.load(HookTool.CLAUDE_CODE);
        assertThat(content).contains("permissionDecision");
        assertThat(content).contains("deny");
        assertThat(content).contains("condense ");
    }
}
