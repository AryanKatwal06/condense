package com.condense.commands;

import com.condense.core.PlatformDirs;
import com.condense.core.TeeFileReader;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Inspect a line range from a raw tee file. MCP clients may also use the expand tool. */
@Command(name = "expand", mixinStandardHelpOptions = true,
    description = "Inspect omitted line ranges from a Condense tee recovery file.",
    footer = {"", "Examples:", "  condense expand 1234-git-diff.txt 50 60",
        "  condense expand 1234-git-diff.txt --start 50 --end 60"})
@Dependent
public class ExpandCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "1", paramLabel = "TEE_FILE",
        description = "Tee filename or path inside the Condense tee directory.")
    String teeFile;

    @Parameters(index = "1", arity = "0..1", paramLabel = "START_LINE")
    Integer positionalStart;

    @Parameters(index = "2", arity = "0..1", paramLabel = "END_LINE")
    Integer positionalEnd;

    @Option(names = {"-s", "--start"}, paramLabel = "LINE",
        description = "First line to print, 1-based (default 1).")
    Integer startOption;

    @Option(names = {"-e", "--end"}, paramLabel = "LINE",
        description = "Last line to print, inclusive (default at most 100 lines).")
    Integer endOption;

    @Inject
    PlatformDirs platformDirs;

    private final PrintStream out;
    private final PrintStream err;

    public ExpandCommand() {
        this(null, System.out, System.err);
    }

    public ExpandCommand(PlatformDirs platformDirs, PrintStream out, PrintStream err) {
        this.platformDirs = platformDirs;
        this.out = out == null ? System.out : out;
        this.err = err == null ? System.err : err;
    }

    @Override
    public Integer call() {
        if (teeFile == null || teeFile.isBlank()) {
            err.println("condense expand: tee file is required");
            return 2;
        }
        int start = startOption != null ? startOption
            : positionalStart != null ? positionalStart : 1;
        Integer end = endOption != null ? endOption : positionalEnd;
        if (start < 1 || (end != null && end < start)) {
            err.println("condense expand: line range must be 1-based and end at or after start");
            return 2;
        }
        PlatformDirs dirs = platformDirs == null ? new PlatformDirs() : platformDirs;
        TeeFileReader.Result result;
        try {
            result = TeeFileReader.read(Path.of(teeFile), dirs.getDataDir(), start, end);
        } catch (RuntimeException e) {
            err.println("condense expand: invalid tee file path");
            return 1;
        }
        if (!result.ok()) {
            err.println("condense expand: " + result.error());
            return 1;
        }
        out.print(result.content());
        out.flush();
        return 0;
    }
}
