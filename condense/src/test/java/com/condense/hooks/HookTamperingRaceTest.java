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

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HookTamperingRaceTest {

    private static Path suiteData;
    private static Path suiteConfig;

    @BeforeAll
    static void isolateSuiteDirs() throws IOException {
        suiteData = Files.createTempDirectory("condense-tamper-suite-data");
        suiteConfig = Files.createTempDirectory("condense-tamper-suite-config");
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
    void midRunScriptTruncation_detectedAsTampered() throws IOException {
        Path tempHome = Files.createTempDirectory("condense-tamper-home");
        try {
            System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());

            installer.install(HookTool.CURSOR);
            Path script = HookTool.CURSOR.ownedScript(tempHome);
            assertThat(Files.exists(script)).isTrue();

            // Truncate to 0 bytes
            Files.writeString(script, "");

            HookInstaller.StatusResult status = installer.showAll().stream()
                .filter(r -> r.tool() == HookTool.CURSOR).findFirst().orElseThrow();

            assertThat(status.installed()).isTrue();
            assertThat(status.integrity()).isEqualTo(HookIntegrity.TAMPERED);

            // Update heals it back to OK
            installer.update(HookTool.CURSOR);
            HookInstaller.StatusResult afterUpdate = installer.showAll().stream()
                .filter(r -> r.tool() == HookTool.CURSOR).findFirst().orElseThrow();
            assertThat(afterUpdate.integrity()).isEqualTo(HookIntegrity.OK);
        } finally {
            System.clearProperty("condense.test.home");
        }
    }

    @Test
    void midRunScriptDeletion_detectedAsMissing(@TempDir Path tempHome) throws IOException {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());

        installer.install(HookTool.CLAUDE_CODE);
        Path script = HookTool.CLAUDE_CODE.ownedScript(tempHome);
        assertThat(Files.exists(script)).isTrue();

        // Delete script externally
        Files.delete(script);

        HookInstaller.StatusResult status = installer.showAll().stream()
            .filter(r -> r.tool() == HookTool.CLAUDE_CODE).findFirst().orElseThrow();

        assertThat(status.integrity()).isEqualTo(HookIntegrity.MISSING);

        // Update recovers script
        installer.update(HookTool.CLAUDE_CODE);
        assertThat(Files.exists(script)).isTrue();
        HookInstaller.StatusResult afterUpdate = installer.showAll().stream()
            .filter(r -> r.tool() == HookTool.CLAUDE_CODE).findFirst().orElseThrow();
        assertThat(afterUpdate.integrity()).isEqualTo(HookIntegrity.OK);
    }

    @Test
    void tamperingAcrossAllTools_healsCorrectly(@TempDir Path tempHome) throws IOException {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());

        // Install all
        installer.installAll();

        // Tamper with every owned script
        for (HookTool tool : HookTool.values()) {
            Path script = tool.ownedScript(tempHome);
            if (Files.exists(script)) {
                Files.writeString(script, "# tampered injection\n");
            }
        }

        // Verify all report TAMPERED or UNMANAGED
        for (HookInstaller.StatusResult status : installer.showAll()) {
            if (status.installed()) {
                assertThat(status.integrity()).isIn(HookIntegrity.TAMPERED, HookIntegrity.UNMANAGED);
            }
        }

        // Run updateAll
        var updateResults = installer.updateAll();
        assertThat(updateResults).isNotEmpty();

        // Verify all now report OK
        for (HookInstaller.StatusResult status : installer.showAll()) {
            if (status.installed()) {
                assertThat(status.integrity()).isEqualTo(HookIntegrity.OK);
            }
        }
    }
}
