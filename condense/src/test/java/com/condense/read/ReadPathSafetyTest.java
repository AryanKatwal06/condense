package com.condense.read;

import com.condense.core.SafePathValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class ReadPathSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void workspaceRootFindsGitMarker() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path nested = repo.resolve("src");
        Files.createDirectories(nested);
        Files.createDirectories(repo.resolve(".git"));
        assertThat(SafePathValidator.resolveWorkspaceRoot(nested)).isEqualTo(repo.toAbsolutePath().normalize());
    }

    @Test
    void workspaceRootFallsBackToCwd() {
        Path root = SafePathValidator.resolveWorkspaceRoot(tempDir);
        assertThat(root).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    @Test
    void containReadableRejectsDirectory() throws Exception {
        Path dir = tempDir.resolve("src");
        Files.createDirectories(dir);
        SafePathValidator.ContainmentResult result = SafePathValidator.containReadable(dir, tempDir);
        assertThat(result.contained()).isFalse();
        assertThat(result.reason()).contains("directory");
    }

    @Test
    void containReadableRejectsPathOutsideRoot() throws Exception {
        Path inside = tempDir.resolve("proj");
        Files.createDirectories(inside);
        Path outside = tempDir.resolve("secret.txt");
        Files.writeString(outside, "nope");
        SafePathValidator.ContainmentResult result = SafePathValidator.containReadable(outside, inside);
        assertThat(result.contained()).isFalse();
    }

    @Test
    void rootOverrideCannotWidenWorkspace() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve("ok.txt"), "ok");
        Path outside = tempDir.resolve("other");
        Files.createDirectories(outside);
        ReadPathGate.GateResult result = ReadPathGate.openFile(
            repo.resolve("ok.txt"), repo, outside, ReadPathGate.DEFAULT_MAX_BYTES);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("narrow");
    }

    @Test
    void oversizeFileIsRejected() throws Exception {
        Path file = tempDir.resolve("big.txt");
        Files.writeString(file, "x".repeat(64));
        ReadPathGate.GateResult result = ReadPathGate.openFile(file, tempDir, tempDir, 16);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("cap");
    }

    @Test
    void binaryFileIsRejected() throws Exception {
        Path file = tempDir.resolve("blob.bin");
        Files.write(file, new byte[] {1, 2, 0, 3});
        ReadPathGate.GateResult result = ReadPathGate.openFile(
            file, tempDir, tempDir, ReadPathGate.DEFAULT_MAX_BYTES);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("binary");
    }

    @Test
    void symlinkEscapeIsRejected() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "secret");
        Path link = repo.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (Exception e) {
            assumeThat(false).as("symlinks not available").isTrue();
            return;
        }
        ReadPathGate.GateResult result = ReadPathGate.openFile(
            link, repo, repo, ReadPathGate.DEFAULT_MAX_BYTES);
        assertThat(result.ok()).isFalse();
    }
}
