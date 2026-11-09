package com.condense.commands;

import com.condense.core.IsolatedPlatformDirs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExpandCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void printsRequestedLineSlice() throws Exception {
        IsolatedPlatformDirs dirs = dirs();
        Path tee = dirs.getDataDir().resolve("tee");
        Files.createDirectories(tee);
        Files.writeString(tee.resolve("sample.txt"), "one\ntwo\nthree\nfour\n");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ExpandCommand command = new ExpandCommand(dirs,
            new PrintStream(bytes), new PrintStream(new ByteArrayOutputStream()));
        command.teeFile = "sample.txt";
        command.positionalStart = 2;
        command.positionalEnd = 3;

        assertThat(command.call()).isZero();
        assertThat(bytes.toString()).isEqualTo("two\nthree\n");
    }

    @Test
    void picocliAcceptsNamedLineOptions() throws Exception {
        IsolatedPlatformDirs dirs = dirs();
        Path tee = dirs.getDataDir().resolve("tee");
        Files.createDirectories(tee);
        Files.writeString(tee.resolve("options.txt"), "one\ntwo\nthree\nfour\n");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ExpandCommand command = new ExpandCommand(dirs,
            new PrintStream(bytes), new PrintStream(new ByteArrayOutputStream()));

        int exitCode = new CommandLine(command).execute(
            "options.txt", "--start", "2", "--end", "3");

        assertThat(exitCode).isZero();
        assertThat(bytes.toString()).isEqualTo("two\nthree\n");
    }

    @Test
    void rejectsMissingAndTraversalPaths() {
        IsolatedPlatformDirs dirs = dirs();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        ExpandCommand missing = new ExpandCommand(dirs,
            new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        missing.teeFile = "missing.txt";
        assertThat(missing.call()).isNotZero();

        ExpandCommand traversal = new ExpandCommand(dirs,
            new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        traversal.teeFile = "../../outside.txt";
        assertThat(traversal.call()).isNotZero();
    }

    private IsolatedPlatformDirs dirs() {
        return new IsolatedPlatformDirs(tempDir.resolve("config"), tempDir.resolve("data"));
    }
}
