package com.condense.hooks;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

@QuarkusTest
public class CursorVerificationTest {

    @Inject
    HookInstaller installer;

    @Test
    public void generateVerificationOutput(@TempDir Path tempHome) throws Exception {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
        try {
            System.out.println("=== 4. CONFIRM DIRECTORY ===");
            System.out.println("Isolated Test Home: " + tempHome.toAbsolutePath().toString());
            System.out.println("============================\n");

            System.out.println("=== 2. CLEAN INSTALL hooks.json ===");
            installer.install(HookTool.CURSOR);
            Path hooksJson = tempHome.resolve(".cursor/hooks.json");
            System.out.println(Files.readString(hooksJson));
            System.out.println("===================================\n");

            System.out.println("=== 1. GENERATED HOOK SCRIPT ===");
            Path hookScript = tempHome.resolve(".cursor/hooks/condense-hook.sh");
            System.out.println(Files.readString(hookScript));
            System.out.println("================================\n");

        } finally {
            System.clearProperty("condense.test.home");
        }
    }

    @Test
    public void generateMergeTestOutput(@TempDir Path tempHome) throws Exception {
        System.setProperty("condense.test.home", tempHome.toAbsolutePath().toString());
        try {
            System.out.println("=== 3. MERGE TEST against isolated fixture ===");
            Path hooksJson = tempHome.resolve(".cursor/hooks.json");
            Files.createDirectories(hooksJson.getParent());
            String existingFixture = """
{
  "version": 1,
  "hooks": {
    "beforeShellExecution": [
      {
        "command": "/bin/existing-hook.sh"
      }
    ]
  }
}
""";
            Files.writeString(hooksJson, existingFixture);
            installer.install(HookTool.CURSOR);
            System.out.println(Files.readString(hooksJson));
            System.out.println("============================================\n");

        } finally {
            System.clearProperty("condense.test.home");
        }
    }
}
