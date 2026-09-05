package com.condense.hooks;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@QuarkusTest
class HookInstallerTest {

    private static Path suiteData;
    private static Path suiteConfig;

    @BeforeAll
    static void isolateSuiteDirs() throws IOException {
        suiteData = Files.createTempDirectory("condense-hook-suite-data");
        suiteConfig = Files.createTempDirectory("condense-hook-suite-config");
        System.setProperty("CONDENSE_DATA_DIR", suiteData.toAbsolutePath().toString());
        System.setProperty("CONDENSE_CONFIG_DIR", suiteConfig.toAbsolutePath().toString());
    }

    @AfterAll
    static void clearSuiteDirs() {
        System.clearProperty("CONDENSE_DATA_DIR");
        System.clearProperty("CONDENSE_CONFIG_DIR");
    }

    @BeforeEach
    void setUp(@TempDir Path tempHome) {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("condense.test.home");
    }

    @Inject
    HookInstaller installer;

    @Test
    void hookTemplateLoadSucceeds_claudeCode() throws IOException {
        // Verify the template resource exists on the classpath
        String content = HookTemplate.load(HookTool.CLAUDE_CODE);
        assertThat(content).isNotBlank();
        assertThat(content).contains(HookTemplate.SENTINEL);
        assertThat(content).contains("CONDENSE_COMMANDS");
        assertThat(content).contains("permissionDecision");
        assertThat(content).contains("deny");
        assertThat(content).contains("condense ");
        assertThat(content).doesNotContain("updatedInput");
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
    void isManagedByCondense_returnsTrueForCondenseContent() {
        String content = "# Installed by: condense init -g\nexec condense \"$@\"";
        assertThat(HookTemplate.isManagedByCondense(content)).isTrue();
    }

    @Test
    void isManagedByCondense_returnsFalseForOtherContent() {
        assertThat(HookTemplate.isManagedByCondense("#!/bin/bash\necho hello")).isFalse();
        assertThat(HookTemplate.isManagedByCondense(null)).isFalse();
    }

    @Test
    void hookToolFileReturnsCorrectPath() {
        Path home = Path.of("/home/user");
        Path expected = home.resolve(".claude/settings.json");
        assertThat(HookTool.CLAUDE_CODE.hookFile(home)).isEqualTo(expected);
    }

    @Test
    void templateApply_excludesCommandFromList() throws IOException {
        String template = HookTemplate.load(HookTool.CLAUDE_CODE);
        String applied = HookTemplate.apply(HookTool.CLAUDE_CODE, template, List.of("curl", "playwright"));
        assertThat(applied).doesNotContain(" curl ");
    }

    @Test
    void showAll_returnsOneResultPerTool() {
        var results = installer.showAll();
        assertThat(results).hasSize(HookTool.values().length);
    }

    @Test
    void geminiInstall_mergesWithExistingSettings(@TempDir Path tempHome) throws IOException {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
        Path settingsFile = HookTool.GEMINI.hookFile(tempHome);
        Files.createDirectories(settingsFile.getParent());
        
        String preSeeded = """
            {
              "theme": "dark",
              "hooks": {
                "BeforeTool": [
                  {
                    "matcher": "write_file",
                    "hooks": [
                      { "name": "other-hook", "type": "command", "command": "something.sh" }
                    ]
                  }
                ]
              }
            }
            """;
        Files.writeString(settingsFile, preSeeded);
        
        HookInstaller.InstallResult result = installer.install(HookTool.GEMINI);
        assertThat(result.success()).isTrue();
        
        String after = Files.readString(settingsFile);
        assertThat(after).contains("\"theme\" : \"dark\"");
        assertThat(after).contains("\"matcher\" : \"write_file\"");
        assertThat(after).contains("\"matcher\" : \"run_shell_command\"");
        assertThat(after).contains("\"name\" : \"condense-hook\"");
    }

    @Test
    void geminiInstall_competingHookWarning(@TempDir Path tempHome) throws IOException {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
        Path settingsFile = HookTool.GEMINI.hookFile(tempHome);
        Files.createDirectories(settingsFile.getParent());
        
        String preSeeded = """
            {
              "hooks": {
                "BeforeTool": [
                  {
                    "matcher": "run_shell_command",
                    "hooks": [
                      { "name": "competitor", "type": "command", "command": "comp.sh" }
                    ]
                  }
                ]
              }
            }
            """;
        Files.writeString(settingsFile, preSeeded);
        
        HookInstaller.InstallResult result = installer.install(HookTool.GEMINI);
        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("Note: an existing BeforeTool hook matching 'run_shell_command'");
    }

    @Test
    void geminiRemove_onlyDeletesCondenseEntry(@TempDir Path tempHome) throws IOException {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
        Path settingsFile = HookTool.GEMINI.hookFile(tempHome);
        Files.createDirectories(settingsFile.getParent());
        
        String preSeeded = """
            {
              "theme": "dark",
              "hooks": {
                "BeforeTool": [
                  {
                    "matcher": "write_file",
                    "hooks": [
                      { "name": "other-hook", "type": "command", "command": "something.sh" }
                    ]
                  },
                  {
                    "matcher": "run_shell_command",
                    "hooks": [
                      { "name": "condense-hook", "type": "command", "command": "condense.sh" }
                    ]
                  }
                ]
              }
            }
            """;
        Files.writeString(settingsFile, preSeeded);
        
        HookInstaller.RemoveResult result = installer.removeAll().stream()
            .filter(r -> r.tool() == HookTool.GEMINI).findFirst().orElseThrow();
        assertThat(result.removed()).isTrue();
        
        String after = Files.readString(settingsFile);
        assertThat(after).contains("\"theme\" : \"dark\"");
        assertThat(after).contains("\"matcher\" : \"write_file\"");
        assertThat(after).doesNotContain("\"name\" : \"condense-hook\"");
    }

    @Test
    void listInstalled_returnsEmptyWhenNoneInstalled() {
        List<HookTool> installed = installer.listInstalled();
        assertThat(installed).isEmpty();
    }

    @Test
    void listInstalled_returnsInstalledToolWhenSingleInstalled(@TempDir Path tempHome) {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
        installer.install(HookTool.GEMINI);
        List<HookTool> installed = installer.listInstalled();
        assertThat(installed).containsExactly(HookTool.GEMINI);
    }

    @Test
    void listInstalled_returnsAllInstalledTools(@TempDir Path tempHome) {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
        List<HookInstaller.InstallResult> results = installer.installAll();
        List<HookTool> installed = installer.listInstalled();
        
        List<HookTool> expectedSuccessfulTools = results.stream()
            .filter(HookInstaller.InstallResult::success)
            .map(HookInstaller.InstallResult::tool)
            .toList();

        assertThat(installed).containsExactlyInAnyOrderElementsOf(expectedSuccessfulTools);
    }

    @Test
    void geminiInstall_backsUpExistingSettings(@TempDir Path tempHome) throws IOException {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
        Path settingsFile = HookTool.GEMINI.hookFile(tempHome);
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, "{ \"theme\": \"dark\" }\n");

        assertThat(installer.install(HookTool.GEMINI).success()).isTrue();
        try (var stream = Files.list(suiteData.resolve("backups"))) {
            assertThat(stream.anyMatch(p -> p.getFileName().toString().startsWith("gemini-"))).isTrue();
        }
    }

    @Test
    void cursorInstall_baselineThenTamper(@TempDir Path tempHome) throws IOException {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());

        assertThat(installer.install(HookTool.CURSOR).success()).isTrue();
        HookInstaller.StatusResult ok = installer.showAll().stream()
            .filter(r -> r.tool() == HookTool.CURSOR).findFirst().orElseThrow();
        assertThat(ok.installed()).isTrue();
        assertThat(ok.integrity()).isEqualTo(HookIntegrity.OK);

        Path script = HookTool.CURSOR.ownedScript(tempHome);
        Files.writeString(script, Files.readString(script) + "\n# tampered\n");
        HookInstaller.StatusResult tampered = installer.showAll().stream()
            .filter(r -> r.tool() == HookTool.CURSOR).findFirst().orElseThrow();
        assertThat(tampered.integrity()).isEqualTo(HookIntegrity.TAMPERED);
    }

    @Test
    void newAgents_installIntoIsolatedHome(@TempDir Path tempHome) {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
        for (HookTool tool : List.of(
            HookTool.CODEX, HookTool.OPENCODE, HookTool.KILO,
            HookTool.ANTIGRAVITY, HookTool.HERMES, HookTool.PI
        )) {
            HookInstaller.InstallResult result = installer.install(tool);
            assertThat(result.success()).as(result.message()).isTrue();
            assertThat(Files.exists(tool.ownedScript(tempHome)))
                .as("%s owned script", tool.displayName)
                .isTrue();
            HookInstaller.StatusResult status = installer.showAll().stream()
                .filter(r -> r.tool() == tool).findFirst().orElseThrow();
            assertThat(status.installed()).isTrue();
            assertThat(status.integrity()).isEqualTo(HookIntegrity.OK);
        }
    }
}
