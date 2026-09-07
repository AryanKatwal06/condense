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

@QuarkusTest
class HookDryRunAndUpdateTest {

    private static Path suiteData;
    private static Path suiteConfig;

    @BeforeAll
    static void isolateSuiteDirs() throws IOException {
        suiteData = Files.createTempDirectory("condense-dryrun-suite-data");
        suiteConfig = Files.createTempDirectory("condense-dryrun-suite-config");
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
    void plan_makesZeroDiskModifications() throws IOException {
        Path tempHome = Files.createTempDirectory("condense-dryrun-home");
        try {
            System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());

            List<HookInstaller.PlanResult> plans = installer.planAll();
            assertThat(plans).hasSize(HookTool.values().length);

            for (HookInstaller.PlanResult plan : plans) {
                assertThat(plan.action()).isEqualTo("INSTALL");
                assertThat(plan.installed()).isFalse();
                assertThat(plan.willBackup()).isFalse();
                assertThat(plan.targetPath()).isNotNull();
                assertThat(plan.scriptPath()).isNotNull();
            }

            // Entire temp directory must remain completely empty after planning
            try (var stream = Files.list(tempHome)) {
                assertThat(stream.count()).isZero();
            }
        } finally {
            System.clearProperty("condense.test.home");
        }
    }

    @Test
    void plan_detectsExistingConfigAndFlagsBackupRequired(@TempDir Path tempHome) throws IOException {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
        Path claudeSettings = HookTool.CLAUDE_CODE.hookFile(tempHome);
        Files.createDirectories(claudeSettings.getParent());
        Files.writeString(claudeSettings, "{\"theme\": \"system\"}");

        Path backupsDir = suiteData.resolve("backups");
        long backupsBefore = Files.exists(backupsDir) ? countFiles(backupsDir) : 0;

        HookInstaller.PlanResult plan = installer.plan(HookTool.CLAUDE_CODE);
        assertThat(plan.installed()).isFalse();
        assertThat(plan.willBackup()).isTrue();
        assertThat(plan.action()).isEqualTo("INSTALL");

        // Verify that planning did not create any backups or modify claudeSettings
        assertThat(Files.readString(claudeSettings)).isEqualTo("{\"theme\": \"system\"}");
        long backupsAfter = Files.exists(backupsDir) ? countFiles(backupsDir) : 0;
        assertThat(backupsAfter).isEqualTo(backupsBefore);
    }

    private long countFiles(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.count();
        }
    }

    @Test
    void update_isIdempotentAndProducesZeroDuplicates(@TempDir Path tempHome) throws IOException {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
        Path cursorSettings = HookTool.CURSOR.hookFile(tempHome);
        Files.createDirectories(cursorSettings.getParent());
        Files.writeString(cursorSettings, "{\"custom_key\": \"value\"}");

        // First install
        HookInstaller.InstallResult r1 = installer.install(HookTool.CURSOR);
        assertThat(r1.success()).isTrue();

        String afterFirst = Files.readString(cursorSettings);
        assertThat(afterFirst).contains("\"custom_key\" : \"value\"");
        assertThat(afterFirst).contains("condense-hook.sh");

        // First update
        HookInstaller.InstallResult u1 = installer.update(HookTool.CURSOR);
        assertThat(u1.success()).isTrue();

        String afterSecond = Files.readString(cursorSettings);
        assertThat(afterSecond).contains("\"custom_key\" : \"value\"");

        // Count occurrences of condense-hook.sh in the config — MUST BE EXACTLY 1 (no duplicate entries)
        int firstOccur = afterSecond.indexOf("condense-hook.sh");
        int secondOccur = afterSecond.indexOf("condense-hook.sh", firstOccur + 1);
        assertThat(firstOccur).isGreaterThanOrEqualTo(0);
        assertThat(secondOccur).isEqualTo(-1);

        // Third update
        HookInstaller.InstallResult u2 = installer.update(HookTool.CURSOR);
        assertThat(u2.success()).isTrue();

        String afterThird = Files.readString(cursorSettings);
        int thirdOccur = afterThird.indexOf("condense-hook.sh", afterThird.indexOf("condense-hook.sh") + 1);
        assertThat(thirdOccur).isEqualTo(-1);
    }

    @Test
    void update_healsTamperedHookToPristineIntegrity(@TempDir Path tempHome) throws IOException {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());

        installer.install(HookTool.GEMINI);
        HookInstaller.StatusResult okStatus = installer.showAll().stream()
            .filter(r -> r.tool() == HookTool.GEMINI).findFirst().orElseThrow();
        assertThat(okStatus.integrity()).isEqualTo(HookIntegrity.OK);

        // Tamper with owned script
        Path script = HookTool.GEMINI.ownedScript(tempHome);
        Files.writeString(script, "# tampered script\n");

        HookInstaller.StatusResult tamperedStatus = installer.showAll().stream()
            .filter(r -> r.tool() == HookTool.GEMINI).findFirst().orElseThrow();
        assertThat(tamperedStatus.integrity()).isEqualTo(HookIntegrity.TAMPERED);

        // Plan should indicate RESTORE action
        HookInstaller.PlanResult plan = installer.plan(HookTool.GEMINI);
        assertThat(plan.action()).isEqualTo("RESTORE");

        // Run update to heal
        HookInstaller.InstallResult updateResult = installer.update(HookTool.GEMINI);
        assertThat(updateResult.success()).isTrue();

        // Verify integrity is restored to OK
        HookInstaller.StatusResult healedStatus = installer.showAll().stream()
            .filter(r -> r.tool() == HookTool.GEMINI).findFirst().orElseThrow();
        assertThat(healedStatus.integrity()).isEqualTo(HookIntegrity.OK);
        assertThat(Files.readString(script)).contains(HookTemplate.SENTINEL);
    }

    @Test
    void remove_cleansCondenseEntriesWhilePreservingThirdPartyConfig(@TempDir Path tempHome) throws IOException {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
        Path claudeSettings = HookTool.CLAUDE_CODE.hookFile(tempHome);
        Files.createDirectories(claudeSettings.getParent());
        Files.writeString(claudeSettings, "{\"user_pref\": 42, \"model\": \"claude-3-5\"}");

        installer.install(HookTool.CLAUDE_CODE);
        assertThat(Files.readString(claudeSettings)).contains("condense-hook.sh");

        HookInstaller.RemoveResult removeResult = installer.remove(HookTool.CLAUDE_CODE);
        assertThat(removeResult.removed()).isTrue();

        String afterRemove = Files.readString(claudeSettings);
        assertThat(afterRemove).contains("\"user_pref\" : 42");
        assertThat(afterRemove).contains("\"model\" : \"claude-3-5\"");
        assertThat(afterRemove).doesNotContain("condense-hook.sh");

        // Owned script should be deleted
        assertThat(Files.exists(HookTool.CLAUDE_CODE.ownedScript(tempHome))).isFalse();
    }
}
