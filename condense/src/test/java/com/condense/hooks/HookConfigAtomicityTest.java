package com.condense.hooks;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.persist.AtomicFile;
import com.condense.persist.FaultyDurableIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HookConfigAtomicityTest {

    @TempDir
    Path tempDir;

    @Test
    void partialWriteLeavesLastGoodBytes() throws Exception {
        Path config = tempDir.resolve("cfg");
        Path data = tempDir.resolve("data");
        Files.createDirectories(data);
        IsolatedPlatformDirs dirs = new IsolatedPlatformDirs(config, data);
        Path hookFile = tempDir.resolve("agent").resolve("hooks.json");
        Files.createDirectories(hookFile.getParent());
        String original = "{\"hooks\":[]}";
        Files.writeString(hookFile, original);

        HookInstaller installer = new HookInstaller();
        installer.platformDirs = dirs;
        installer.useAtomicFile(new AtomicFile(FaultyDurableIo.diskFullAfter(6)));

        assertThatThrownBy(() -> installer.writeThirdPartyConfig(
            HookTool.CURSOR,
            hookFile,
            "{\"hooks\":[1,2,3,4,5,6,7,8,9,10]}"))
            .isInstanceOf(IOException.class);

        assertThat(Files.readString(hookFile)).isEqualTo(original);
    }
}
