package com.condense.hooks;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.TrackingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HookIntegrityTest {

    @TempDir
    Path tempDir;

    @Test
    void missingUnmanagedOkAndTampered() throws Exception {
        Path script = tempDir.resolve("condense-hook.sh");
        assertThat(HookIntegrity.verify(null, HookTool.CURSOR, script)).isEqualTo(HookIntegrity.MISSING);

        Files.writeString(script, "#!/bin/sh\necho unmanaged\n");
        assertThat(HookIntegrity.verify(null, HookTool.CURSOR, script)).isEqualTo(HookIntegrity.UNMANAGED);

        Files.writeString(script, HookTemplate.SENTINEL + "\necho ok\n");
        IsolatedPlatformDirs dirs = new IsolatedPlatformDirs(tempDir.resolve("cfg"), tempDir.resolve("data"));
        TrackingRepository tracking = new TrackingRepository(dirs);
        try {
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
