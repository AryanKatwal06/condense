package com.zapproxy.hooks;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@QuarkusTest
class HookInstallerTest {

    @Inject
    HookInstaller installer;

    @Test
    void hookTemplateLoadSucceeds_claudeCode() throws IOException {
        // Verify the template resource exists on the classpath
        String content = HookTemplate.load(HookTool.CLAUDE_CODE);
        assertThat(content).isNotBlank();
        assertThat(content).contains(HookTemplate.SENTINEL);
        assertThat(content).contains("ZAP_COMMANDS");
        assertThat(content).contains("exec zap");
    }

    @Test
    void hookTemplateLoadSucceeds_allTools() {
        for (HookTool tool : HookTool.values()) {
            assertThatCode(() -> HookTemplate.load(tool))
                .as("Template for %s must load without IOException", tool.displayName)
                .doesNotThrowAnyException();
        }
    }

    @Test
    void isManagedByZap_returnsTrueForZapContent() {
        String content = "# Installed by: zap init -g\nexec zap \"$@\"";
        assertThat(HookTemplate.isManagedByZap(content)).isTrue();
    }

    @Test
    void isManagedByZap_returnsFalseForOtherContent() {
        assertThat(HookTemplate.isManagedByZap("#!/bin/bash\necho hello")).isFalse();
        assertThat(HookTemplate.isManagedByZap(null)).isFalse();
    }

    @Test
    void hookToolFileReturnsCorrectPath() {
        Path home = Path.of("/home/user");
        Path expected = home.resolve(".claude/hooks/pre-tool-use.sh");
        assertThat(HookTool.CLAUDE_CODE.hookFile(home)).isEqualTo(expected);
    }

    @Test
    void templateApply_excludesCommandFromList() throws IOException {
        String template = HookTemplate.load(HookTool.CLAUDE_CODE);
        String applied = HookTemplate.apply(template, List.of("curl", "playwright"));
        assertThat(applied).doesNotContain(" curl ");
    }

    @Test
    void showAll_returnsOneResultPerTool() {
        var results = installer.showAll();
        assertThat(results).hasSize(HookTool.values().length);
    }
}
