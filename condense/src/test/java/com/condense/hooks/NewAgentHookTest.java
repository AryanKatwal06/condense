package com.condense.hooks;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.TrackingRepository;
import jakarta.enterprise.inject.Vetoed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewAgentHookTest {

    @TempDir
    Path home;

    private TrackingRepository tracking;
    private HookInstaller installer;

    @BeforeEach
    void setUp() {
        System.setProperty("condense.test.home", home.toAbsolutePath().toString());
        IsolatedPlatformDirs dirs = new IsolatedPlatformDirs(home.resolve("cfg"), home.resolve("data"));
        tracking = new TrackingRepository(dirs);
        installer = new HookInstaller();
        installer.configLoader = new EmptyConfig();
        installer.platformDirs = dirs;
        installer.tracking = tracking;
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("condense.test.home");
        if (tracking != null) {
            tracking.close();
        }
    }

    @Test
    void sixNewAgentsInstallAndRecordAnInstallRow() throws Exception {
        for (HookTool tool : List.of(
            HookTool.CODEX, HookTool.OPENCODE, HookTool.KILO,
            HookTool.ANTIGRAVITY, HookTool.HERMES, HookTool.PI
        )) {
            HookInstaller.InstallResult result = installer.install(tool);
            assertThat(result.success()).as(result.message()).isTrue();
            assertThat(Files.exists(tool.ownedScript(home))).isTrue();
            assertThat(HookTemplate.isManagedByCondense(Files.readString(tool.ownedScript(home)))).isTrue();
            HookInstaller.StatusResult status = installer.showAll().stream()
                .filter(r -> r.tool() == tool).findFirst().orElseThrow();
            assertThat(status.installed()).isTrue();
            assertThat(status.integrity()).isEqualTo(HookIntegrity.OK);
            assertThat(tracking.findHookBaseline(tool.name())).isNotNull();
        }
        assertThat(tracking.countHookEvents()).isGreaterThanOrEqualTo(6);
        assertThat(tracking.schemaVersion()).isEqualTo(2);
    }

    @Test
    void existingAgentsStillInstall() {
        for (HookTool tool : List.of(HookTool.COPILOT, HookTool.WINDSURF, HookTool.CURSOR)) {
            HookInstaller.InstallResult result = installer.install(tool);
            assertThat(result.success()).as(result.message()).isTrue();
        }
    }

    @Test
    void backupFailureLeavesThirdPartyConfigUntouched() throws Exception {
        Path config = home.resolve(".cursor").resolve("hooks.json");
        Files.createDirectories(config.getParent());
        String original = "{ \"version\": 1, \"keep\": \"mine\" }\n";
        Files.writeString(config, original);

        Path backups = home.resolve("data").resolve("backups");
        Files.createDirectories(backups.getParent());
        Files.writeString(backups, "not-a-directory");

        HookInstaller.InstallResult result = installer.install(HookTool.CURSOR);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("fail");
        assertThat(Files.readString(config)).isEqualTo(original);
    }

    @Vetoed
    static final class EmptyConfig extends com.condense.core.ConfigLoader {
        @Override
        public com.condense.core.CondenseConfig load() {
            return new com.condense.core.CondenseConfig(
                new com.condense.core.CondenseConfig.HooksConfig(List.of()),
                new com.condense.core.CondenseConfig.TeeConfig(true, com.condense.core.TeeMode.FAILURES),
                java.util.Map.of()
            );
        }
    }
}
