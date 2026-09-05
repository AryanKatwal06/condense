package com.condense.discover;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoverCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void jsonEmptyRepoExitsZero() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("empty"));
        Files.createDirectories(root.resolve(".git"));
        String out = withCwd(root, () -> {
            DiscoverCommand command = new DiscoverCommand();
            command.format = "json";
            return capture(() -> assertThat(command.call()).isZero());
        });
        assertThat(out).contains("\"schema_version\" : 1");
        assertThat(out).contains("git-status");
    }

    @Test
    void textListsRecommendations() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("js"));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("pnpm-lock.yaml"), "lockfileVersion: 9\n");
        String out = withCwd(root, () -> {
            DiscoverCommand command = new DiscoverCommand();
            command.format = "text";
            return capture(() -> assertThat(command.call()).isZero());
        });
        assertThat(out).contains("pnpm-install");
        assertThat(out).contains("js-install");
    }

    @Test
    void widenRootExitsOne() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("repo"));
        Files.createDirectories(root.resolve(".git"));
        String out = withCwd(root, () -> {
            DiscoverCommand command = new DiscoverCommand();
            command.root = root.getParent();
            return capture(() -> assertThat(command.call()).isEqualTo(1));
        });
        assertThat(out).contains("narrow");
    }

    private static String withCwd(Path cwd, java.util.concurrent.Callable<String> action) throws Exception {
        String previous = System.getProperty("user.dir");
        System.setProperty("user.dir", cwd.toAbsolutePath().toString());
        try {
            return action.call();
        } finally {
            System.setProperty("user.dir", previous);
        }
    }

    private static String capture(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString();
    }
}
