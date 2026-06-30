package com.condense.hooks;

import com.condense.core.ConfigLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ClineVerificationTest {

    @TempDir
    Path testHome;

    private HookInstaller installer;

    @BeforeEach
    void setUp() {
        System.setProperty("condense.test.home", testHome.toString());
        installer = new HookInstaller();
        // We mock config loader to return empty excluded list
        installer.configLoader = new ConfigLoader() {
            @Override
            public com.condense.core.CondenseConfig load() {
                return new com.condense.core.CondenseConfig(
                    new com.condense.core.CondenseConfig.HooksConfig(List.of()),
                    new com.condense.core.CondenseConfig.TeeConfig(true, com.condense.core.TeeMode.FAILURES)
                );
            }
        };
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("condense.test.home");
        System.clearProperty("os.name");
    }

    @Test
    void testCleanInstallAndPermissions() throws IOException {
        System.out.println("=== testCleanInstallAndPermissions ===");
        System.setProperty("os.name", "Linux"); // Force Linux behavior for permission check
        Path expectedHookFile = testHome.resolve("Documents/Cline/Rules/Hooks/PreToolUse");
        
        System.out.println("Test Home: " + testHome);
        System.out.println("Expected File: " + expectedHookFile);
        
        assertFalse(Files.exists(expectedHookFile));

        HookInstaller.InstallResult result = installer.install(HookTool.CLINE);
        
        System.out.println("Install Result: " + result.message());
        assertTrue(result.success());
        assertTrue(Files.exists(expectedHookFile));
        
        System.out.println("Generated Script Content:\n" + Files.readString(expectedHookFile));
        
        // Check permissions programmatically
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(expectedHookFile);
            assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE));
            System.out.println("Permissions: " + perms);
        } catch (UnsupportedOperationException e) {
            System.out.println("Posix permissions not supported on this host programmatically.");
        }
        
        // Execute literal ls -l for output
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ls", "-l", expectedHookFile.toString()});
            p.waitFor();
            byte[] bytes = p.getInputStream().readAllBytes();
            System.out.println("LITERAL ls -l output:");
            System.out.println(new String(bytes));
        } catch (Exception e) {
            System.out.println("Failed to run ls -l: " + e.getMessage());
        }
    }

    @Test
    void testCollisionRefusal() throws IOException {
        System.out.println("=== testCollisionRefusal ===");
        System.setProperty("os.name", "Linux"); // Force Linux behavior
        Path hookDir = testHome.resolve("Documents/Cline/Rules/Hooks");
        Files.createDirectories(hookDir);
        Path hookFile = hookDir.resolve("PreToolUse");
        
        String dummyContent = "#!/bin/bash\necho 'Dummy unmanaged hook'\n";
        Files.writeString(hookFile, dummyContent);
        
        System.out.println("Before install file content:\n" + dummyContent);

        HookInstaller.InstallResult result = installer.install(HookTool.CLINE);
        
        System.out.println("Install Result Message:\n" + result.message());
        assertFalse(result.success());
        assertTrue(result.message().contains("Refusing to overwrite existing unmanaged PreToolUse"));
        
        String afterContent = Files.readString(hookFile);
        System.out.println("After install file content:\n" + afterContent);
        assertEquals(dummyContent, afterContent);
    }

    @Test
    void testWindowsSkip() {
        System.out.println("=== testWindowsSkip ===");
        System.setProperty("os.name", "Windows 11"); // Force Windows behavior
        
        HookInstaller.InstallResult result = installer.install(HookTool.CLINE);
        
        System.out.println("Install Result Message: " + result.message());
        assertFalse(result.success());
        assertTrue(result.message().contains("Cline hooks are not supported on Windows"));
        
        Path expectedHookFile = testHome.resolve("Documents/Cline/Rules/Hooks/PreToolUse");
        assertFalse(Files.exists(expectedHookFile));
    }

    @Test
    void testRemove() throws IOException {
        System.out.println("=== testRemove ===");
        System.setProperty("os.name", "Linux"); // Force Linux behavior
        
        Path hookDir = testHome.resolve("Documents/Cline/Rules/Hooks");
        Files.createDirectories(hookDir);
        Path hookFile = hookDir.resolve("PreToolUse");
        
        // 1. Remove managed file
        installer.install(HookTool.CLINE);
        assertTrue(Files.exists(hookFile));
        System.out.println("Managed file exists before remove: true");
        
        List<HookInstaller.RemoveResult> removeResults = installer.removeAll();
        HookInstaller.RemoveResult clineResult = removeResults.stream().filter(r -> r.tool() == HookTool.CLINE).findFirst().orElseThrow();
        
        System.out.println("Remove Result (Managed): " + clineResult.message());
        assertTrue(clineResult.removed());
        assertFalse(Files.exists(hookFile));
        
        // 2. Try removing unmanaged file
        String dummyContent = "#!/bin/bash\necho 'Dummy unmanaged hook'\n";
        Files.writeString(hookFile, dummyContent);
        System.out.println("\nUnmanaged file created.");
        
        removeResults = installer.removeAll();
        clineResult = removeResults.stream().filter(r -> r.tool() == HookTool.CLINE).findFirst().orElseThrow();
        
        System.out.println("Remove Result (Unmanaged): " + clineResult.message());
        assertFalse(clineResult.removed());
        assertTrue(Files.exists(hookFile));
        assertEquals(dummyContent, Files.readString(hookFile));
        System.out.println("Unmanaged file content unchanged.");
    }
}
