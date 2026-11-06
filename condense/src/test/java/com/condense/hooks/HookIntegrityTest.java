package com.condense.hooks;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.TrackingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HookIntegrityTest {

    @TempDir
    Path tempDir;

    @Test
    void allBundledHooksUseStrictPosixEnvAssignmentParsing() throws Exception {
        var hooksUrl = HookIntegrityTest.class.getResource("/hooks");
        assertThat(hooksUrl).as("bundled hooks resource root").isNotNull();
        Path hooksDir = Path.of(hooksUrl.toURI());

        List<Path> templates;
        try (var walk = Files.walk(hooksDir)) {
            templates = walk.filter(Files::isRegularFile).toList();
        }
        assertThat(templates).isNotEmpty();

        for (Path template : templates) {
            String content = Files.readString(template);
            String name = hooksDir.relativize(template).toString().replace('\\', '/');

            assertThat(content)
                .as("legacy loose env assignment check must be gone from %s", name)
                .doesNotContain("\"=\" in w")
                .doesNotContain("\"=\" in opt")
                .doesNotContain("w.includes(\"=\")")
                .doesNotContain("opt.includes(\"=\")");

            if (content.contains("import re")) {
                assertThat(content)
                    .as("Python hook %s must use the strict POSIX identifier regex", name)
                    .contains("^[A-Za-z_][A-Za-z0-9_]*=");
            }
            if (name.endsWith(".js") || name.endsWith(".ts")) {
                assertThat(content)
                    .as("JavaScript hook %s must use the strict POSIX identifier regex", name)
                    .contains("/^[A-Za-z_][A-Za-z0-9_]*=/");
            }
        }
    }

    @Test
    void missingUnmanagedOkAndTampered() throws Exception {
        Path script = tempDir.resolve("condense-hook.sh");
        assertThat(HookIntegrity.verify(null, HookTool.CURSOR, script)).isEqualTo(HookIntegrity.MISSING);

        Files.writeString(script, "#!/bin/sh\necho unmanaged\n");
        assertThat(HookIntegrity.verify(null, HookTool.CURSOR, script)).isEqualTo(HookIntegrity.UNMANAGED);

        Files.writeString(script, HookTemplate.SENTINEL + "\necho ok\n");
        assertThat(HookIntegrity.verify(null, HookTool.CURSOR, script)).isEqualTo(HookIntegrity.UNMANAGED);
        IsolatedPlatformDirs dirs = new IsolatedPlatformDirs(tempDir.resolve("cfg"), tempDir.resolve("data"));
        TrackingRepository tracking = new TrackingRepository(dirs);
        try {
            assertThat(HookIntegrity.verify(tracking, HookTool.CURSOR, script)).isEqualTo(HookIntegrity.UNMANAGED);
            String sha = HookIntegrity.hashFile(script);
            tracking.upsertHookBaseline("CURSOR", script.toString(), sha);
            assertThat(HookIntegrity.verify(tracking, HookTool.CURSOR, script)).isEqualTo(HookIntegrity.OK);

            Files.writeString(script, Files.readString(script) + "# changed\n");
            assertThat(HookIntegrity.verify(tracking, HookTool.CURSOR, script)).isEqualTo(HookIntegrity.TAMPERED);
        } finally {
            tracking.close();
        }
    }
}
