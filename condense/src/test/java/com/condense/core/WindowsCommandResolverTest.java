package com.condense.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsCommandResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveFindsCmdShimWithoutExtension() throws Exception {
        Path shim = tempDir.resolve("pytest.cmd");
        Files.writeString(shim, "@echo off\r\n");

        assertSameFile(
            WindowsCommandResolver.resolve("pytest", tempDir.toString(), ".COM;.EXE;.BAT;.CMD"),
            shim);
    }

    @Test
    void resolvePrefersPathextOrder() throws Exception {
        Files.writeString(tempDir.resolve("tool.cmd"), "cmd");
        Path exe = tempDir.resolve("tool.exe");
        Files.writeString(exe, "exe");

        assertSameFile(
            WindowsCommandResolver.resolve("tool", tempDir.toString(), ".EXE;.CMD"),
            exe);
    }

    @Test
    void resolvePrefersEarlierPathDirectory() throws Exception {
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Path winner = first.resolve("tool.cmd");
        Files.writeString(winner, "first");
        Files.writeString(second.resolve("tool.cmd"), "second");

        String path = first + ";" + second;
        assertSameFile(WindowsCommandResolver.resolve("tool", path, ".CMD"), winner);
    }

    @Test
    void rewriteWrapsBatchFilesInCmd() throws Exception {
        Path shim = tempDir.resolve("pytest.cmd");
        Files.writeString(shim, "@echo off\r\n");

        List<String> rewritten = WindowsCommandResolver.rewrite(
            List.of("pytest", "tests"), tempDir.toString(), ".CMD");
        assertThat(rewritten).hasSize(4);
        assertThat(rewritten.get(0)).isEqualTo("cmd.exe");
        assertThat(rewritten.get(1)).isEqualTo("/c");
        assertThat(Path.of(rewritten.get(2))).exists();
        assertThat(Files.isSameFile(Path.of(rewritten.get(2)), shim)).isTrue();
        assertThat(rewritten.get(3)).isEqualTo("tests");
    }

    @Test
    void rewriteLeavesUnknownCommandsUnchanged() {
        assertThat(WindowsCommandResolver.rewrite(
            List.of("no-such-condense-shim"), tempDir.toString(), ".CMD"))
            .containsExactly("no-such-condense-shim");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rewrittenCmdShimActuallyRuns() throws Exception {
        Path shim = tempDir.resolve("pytest.cmd");
        Files.writeString(shim, "@echo off\r\necho test_mul failed\r\nexit /b 1\r\n");
        List<String> command = WindowsCommandResolver.rewrite(
            List.of("pytest"), tempDir.toString(), ".CMD");
        Process process = new ProcessBuilder(command).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isEqualTo(1);
        assertThat(stdout).contains("test_mul");
    }

    @Test
    void resolveIgnoresTokensThatAlreadyLookLikePaths() throws Exception {
        Path shim = tempDir.resolve("pytest.cmd");
        Files.writeString(shim, "@echo off\r\n");

        assertThat(WindowsCommandResolver.resolve(
            shim.toString(), tempDir.toString(), ".CMD"))
            .isEmpty();
    }

    private static void assertSameFile(Optional<Path> actual, Path expected) throws Exception {
        assertThat(actual).isPresent();
        assertThat(Files.isSameFile(actual.get(), expected)).isTrue();
    }
}
