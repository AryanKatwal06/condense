package com.condense.uninstall;

import com.condense.core.PlatformDirs;
import com.condense.hooks.HookInstaller;
import com.condense.hooks.HookTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UninstallCommandTest {

    @TempDir
    Path tempDir;

    private Path fakeConfigDir;
    private Path fakeDataDir;
    private Path fakeBinary;
    private PlatformDirs platformDirs;
    private HookInstaller hookInstaller;

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final InputStream originalIn = System.in;
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;

    @BeforeEach
    void setUp() throws IOException {
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        fakeConfigDir = tempDir.resolve("config/condense");
        fakeDataDir = tempDir.resolve("data/condense");
        Files.createDirectories(fakeConfigDir);
        Files.createDirectories(fakeDataDir);

        fakeBinary = tempDir.resolve("bin/condense");
        Files.createDirectories(fakeBinary.getParent());
        Files.writeString(fakeBinary, "binary-content");

        System.setProperty("condense.test.binary", fakeBinary.toString());

        platformDirs = new PlatformDirs() {
            @Override
            public Path resolveConfigDir() {
                return fakeConfigDir;
            }

            @Override
            public Path resolveDataDir() {
                return fakeDataDir;
            }

            @Override
            public Path getConfigDir() {
                return fakeConfigDir;
            }

            @Override
            public Path getDataDir() {
                return fakeDataDir;
            }
        };

        hookInstaller = new HookInstaller() {
            @Override
            public List<RemoveResult> removeAll() {
                return List.of(new RemoveResult(HookTool.CLAUDE_CODE, true, "✓ Removed hook for Claude Code"));
            }

            @Override
            public List<HookTool> listInstalled() {
                return List.of(HookTool.CLAUDE_CODE);
            }
        };
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setIn(originalIn);
        System.clearProperty("condense.test.binary");
        System.clearProperty("condense.test.interactive");
    }

    @Test
    void defaultTier_removesBinaryOnly_leavesDataAndConfig() throws IOException {
        Path db = fakeDataDir.resolve("condense.db");
        Path config = fakeConfigDir.resolve("config.toml");
        Files.writeString(db, "db-content");
        Files.writeString(config, "config-content");

        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = platformDirs;
        cmd.hookInstaller = hookInstaller;
        cmd.purge = false;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(0);

        // Database and config must remain intact
        assertThat(Files.exists(db)).isTrue();
        assertThat(Files.exists(config)).isTrue();
    }

    @Test
    void nonInteractiveGuard_rejectsPurgeWithoutYes() {
        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = platformDirs;
        cmd.hookInstaller = hookInstaller;
        cmd.purge = true;
        cmd.yes = false;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(1);
        assertThat(errContent.toString()).contains("requires '--yes' when running in a non-interactive terminal");
    }

    @Test
    void interactiveConfirmation_rejectsOnNo() throws IOException {
        System.setProperty("condense.test.interactive", "true");
        System.setIn(new ByteArrayInputStream("n\n".getBytes()));

        Path db = fakeDataDir.resolve("condense.db");
        Files.writeString(db, "db");

        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = platformDirs;
        cmd.hookInstaller = hookInstaller;
        cmd.purge = true;
        cmd.yes = false;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(0);
        assertThat(outContent.toString()).contains("Uninstall aborted.");
        assertThat(Files.exists(db)).isTrue();
    }

    @Test
    void interactiveConfirmation_acceptsOnYes() throws IOException {
        System.setProperty("condense.test.interactive", "true");
        System.setIn(new ByteArrayInputStream("y\n".getBytes()));

        Path db = fakeDataDir.resolve("condense.db");
        Files.writeString(db, "db");

        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = platformDirs;
        cmd.hookInstaller = hookInstaller;
        cmd.purge = true;
        cmd.yes = false;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(0);
        assertThat(Files.exists(db)).isFalse();
        assertThat(outContent.toString()).contains("Successfully removed:");
    }

    @Test
    void purgeWithYes_removesDatabaseConfigAndHooks() throws IOException {
        Path db = fakeDataDir.resolve("condense.db");
        Path config = fakeConfigDir.resolve("config.toml");
        Path tracking = fakeDataDir.resolve(".install_dir");
        Files.writeString(db, "db");
        Files.writeString(config, "config");
        Files.writeString(tracking, "/usr/local/bin");

        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = platformDirs;
        cmd.hookInstaller = hookInstaller;
        cmd.purge = true;
        cmd.yes = true;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(0);

        assertThat(Files.exists(db)).isFalse();
        assertThat(Files.exists(config)).isFalse();
        assertThat(Files.exists(tracking)).isFalse();
        assertThat(outContent.toString()).contains("Successfully removed:");
    }

    @Test
    void packageManagerDetection_redirectsAndSkipsBinaryDelete() throws IOException {
        Path scoopBinary = tempDir.resolve("scoop/apps/condense/1.0.1/condense.exe");
        Files.createDirectories(scoopBinary.getParent());
        Files.writeString(scoopBinary, "scoop-bin");
        System.setProperty("condense.test.binary", scoopBinary.toString());

        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = platformDirs;
        cmd.hookInstaller = hookInstaller;
        cmd.purge = false;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(0);
        assertThat(outContent.toString()).contains("Notice: Condense appears to have been installed via Scoop.");
        assertThat(outContent.toString()).contains("scoop uninstall condense");
        // Binary must remain intact
        assertThat(Files.exists(scoopBinary)).isTrue();
    }

    @Test
    void packageManagerDetection_homebrewRedirectsAndSkipsBinaryDelete() throws IOException {
        Path brewBinary = tempDir.resolve("opt/homebrew/Cellar/condense/1.0.1/bin/condense");
        Files.createDirectories(brewBinary.getParent());
        Files.writeString(brewBinary, "brew-bin");
        System.setProperty("condense.test.binary", brewBinary.toString());

        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = platformDirs;
        cmd.hookInstaller = hookInstaller;
        cmd.purge = false;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(0);
        assertThat(outContent.toString()).contains("Notice: Condense appears to have been installed via Homebrew.");
        assertThat(outContent.toString()).contains("brew uninstall condense");
        assertThat(Files.exists(brewBinary)).isTrue();
    }

    @Test
    void packageManagerDetection_wingetRedirectsAndSkipsBinaryDelete() throws IOException {
        Path wingetBinary = tempDir.resolve("AppData/Local/Microsoft/WinGet/Packages/AryanKatwal.Condense_8wekyb3d8bbwe/condense.exe");
        Files.createDirectories(wingetBinary.getParent());
        Files.writeString(wingetBinary, "winget-bin");
        System.setProperty("condense.test.binary", wingetBinary.toString());

        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = platformDirs;
        cmd.hookInstaller = hookInstaller;
        cmd.purge = false;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(0);
        assertThat(outContent.toString()).contains("Notice: Condense appears to have been installed via WinGet.");
        assertThat(outContent.toString()).contains("winget uninstall condense");
        assertThat(Files.exists(wingetBinary)).isTrue();
    }

    @Test
    void interactiveConfirmation_displaysCompletePurgePlan() throws IOException {
        System.setProperty("condense.test.interactive", "true");
        System.setIn(new ByteArrayInputStream("n\n".getBytes()));

        Path tracking = fakeDataDir.resolve(".install_dir");
        Files.writeString(tracking, "/usr/local/bin");

        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = platformDirs;
        cmd.hookInstaller = hookInstaller;
        cmd.purge = true;
        cmd.yes = false;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(0);

        String output = outContent.toString();
        assertThat(output).contains("Condense Uninstall & Purge Plan");
        assertThat(output).contains("• Binary:     " + fakeBinary);
        assertThat(output).contains("• Database:   " + fakeDataDir.resolve("condense.db"));
        assertThat(output).contains("• Config:     " + fakeConfigDir.resolve("config.toml"));
        assertThat(output).contains("• Data dir:   " + fakeDataDir);
        assertThat(output).contains("• Config dir: " + fakeConfigDir);
        assertThat(output).contains("• Metadata:   " + tracking);
        assertThat(output).contains("• Installed AI Hooks:");
        assertThat(output).contains("Claude Code");
        assertThat(output).contains("Uninstall aborted.");
    }

    @Test
    void purge_preservesDirectoryWithUnrecognizedFiles() throws IOException {
        Path db = fakeDataDir.resolve("condense.db");
        Path userDoc = fakeDataDir.resolve("important-user-notes.txt");
        Files.writeString(db, "db");
        Files.writeString(userDoc, "notes");

        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = platformDirs;
        cmd.hookInstaller = hookInstaller;
        cmd.purge = true;
        cmd.yes = true;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(1);

        // condense.db is removed, but directory and user doc are preserved!
        assertThat(Files.exists(db)).isFalse();
        assertThat(Files.exists(userDoc)).isTrue();
        assertThat(Files.exists(fakeDataDir)).isTrue();
        assertThat(outContent.toString()).contains("Left directory");
    }

    @Test
    void partialFailure_returnsExitCodeOneWhenHookFails() {
        HookInstaller failingHooks = new HookInstaller() {
            @Override
            public List<RemoveResult> removeAll() {
                return List.of(new RemoveResult(HookTool.CLAUDE_CODE, false, "✗ Permission denied deleting hook"));
            }

            @Override
            public List<HookTool> listInstalled() {
                return List.of(HookTool.CLAUDE_CODE);
            }
        };

        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = platformDirs;
        cmd.hookInstaller = failingHooks;
        cmd.purge = true;
        cmd.yes = true;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(1);
        assertThat(outContent.toString()).contains("Failed to remove AI hook for Claude Code");
        assertThat(outContent.toString()).contains("Manual cleanup commands:");
    }

    @Test
    void purge_fullScenario_verifiesPromptAndStateAlignment() throws IOException {
        System.setProperty("condense.test.interactive", "true");
        System.setIn(new ByteArrayInputStream("y\n".getBytes()));

        Path db = fakeDataDir.resolve("condense.db");
        Path dbWal = fakeDataDir.resolve("condense.db-wal");
        Path dbShm = fakeDataDir.resolve("condense.db-shm");
        Path config = fakeConfigDir.resolve("config.toml");
        Path tracking = fakeDataDir.resolve(".install_dir");

        Files.writeString(db, "db-data");
        Files.writeString(dbWal, "wal-data");
        Files.writeString(dbShm, "shm-data");
        Files.writeString(config, "config-data");
        Files.writeString(tracking, "/usr/local/bin");

        HookInstaller multiHookInstaller = new HookInstaller() {
            @Override
            public List<RemoveResult> removeAll() {
                return List.of(
                    new RemoveResult(HookTool.CLAUDE_CODE, true, "✓ Removed hook for Claude Code"),
                    new RemoveResult(HookTool.CURSOR, true, "✓ Removed hook for Cursor")
                );
            }

            @Override
            public List<HookTool> listInstalled() {
                return List.of(HookTool.CLAUDE_CODE, HookTool.CURSOR);
            }
        };

        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = platformDirs;
        cmd.hookInstaller = multiHookInstaller;
        cmd.purge = true;
        cmd.yes = false;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(0);

        // 1. Verify confirmation plan output
        String out = outContent.toString();
        assertThat(out).contains("Condense Uninstall & Purge Plan");
        assertThat(out).contains("• Binary:     " + fakeBinary);
        assertThat(out).contains("• Database:   " + db);
        assertThat(out).contains("• Config:     " + config);
        assertThat(out).contains("• Data dir:   " + fakeDataDir);
        assertThat(out).contains("• Config dir: " + fakeConfigDir);
        assertThat(out).contains("• Metadata:   " + tracking);
        assertThat(out).contains("Claude Code");
        assertThat(out).contains("Cursor");

        // 2. Verify all promised files and directories are completely removed
        assertThat(Files.exists(db)).isFalse();
        assertThat(Files.exists(dbWal)).isFalse();
        assertThat(Files.exists(dbShm)).isFalse();
        assertThat(Files.exists(config)).isFalse();
        assertThat(Files.exists(tracking)).isFalse();
        assertThat(Files.exists(fakeDataDir)).isFalse();
        assertThat(Files.exists(fakeConfigDir)).isFalse();

        // 3. Verify summary report shows success
        assertThat(out).contains("Successfully removed:");
        assertThat(out).contains("Removed " + db);
        assertThat(out).contains("Removed " + dbWal);
        assertThat(out).contains("Removed " + dbShm);
        assertThat(out).contains("Removed " + config);
        assertThat(out).contains("Removed install tracking file " + tracking);
        assertThat(out).contains("Removed directory " + fakeDataDir);
        assertThat(out).contains("Removed directory " + fakeConfigDir);
    }

    @Test
    void interactiveConfirmation_unifiedConfigAndDataDir_deduplicatesDirLine() throws IOException {
        System.setProperty("condense.test.interactive", "true");
        System.setIn(new ByteArrayInputStream("n\n".getBytes()));

        PlatformDirs unifiedDirs = new PlatformDirs() {
            @Override
            public Path resolveConfigDir() {
                return fakeDataDir;
            }

            @Override
            public Path resolveDataDir() {
                return fakeDataDir;
            }

            @Override
            public Path getConfigDir() {
                return fakeDataDir;
            }

            @Override
            public Path getDataDir() {
                return fakeDataDir;
            }
        };

        UninstallCommand cmd = new UninstallCommand();
        cmd.platformDirs = unifiedDirs;
        cmd.hookInstaller = hookInstaller;
        cmd.purge = true;
        cmd.yes = false;

        Integer exitCode = cmd.call();
        assertThat(exitCode).isEqualTo(0);

        String out = outContent.toString();
        assertThat(out).contains("• Data dir:   " + fakeDataDir);
        assertThat(out).doesNotContain("• Config dir:");
    }
}

