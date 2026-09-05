package com.condense.read;

import com.condense.core.Mappers;
import com.condense.core.ProjectFingerprint;
import com.condense.core.TrackingRepository;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code condense read} — token-optimized source-file reading.
 *
 * <p>Options must come before the file because the root parser stops at the
 * first positional.
 */
@Command(
    name = "read",
    description = "Read a source file with comment-strip or structural outline.",
    mixinStandardHelpOptions = true
)
@Dependent
@Unremovable
public class ReadCommand implements Callable<Integer> {

    @Option(
        names = "--level",
        description = "verbatim, comments (default), or outline.",
        defaultValue = "comments",
        paramLabel = "LEVEL"
    )
    String level;

    @Option(
        names = "--lang",
        description = "Language name from the builtin catalog.",
        paramLabel = "NAME"
    )
    String lang;

    @Option(
        names = "--root",
        description = "Narrow the workspace containment root.",
        paramLabel = "DIR"
    )
    Path root;

    @Option(
        names = "--max-bytes",
        description = "Read cap in bytes. Default 1048576, hard ceiling 10485760.",
        paramLabel = "N"
    )
    Integer maxBytes;

    @Option(
        names = "--format",
        description = "Output format: 'text' (default) or 'json'.",
        defaultValue = "text",
        paramLabel = "FORMAT"
    )
    String format;

    @Option(
        names = {"-u", "--ultra-compact"},
        description = "Same as --level outline."
    )
    boolean ultraCompact;

    @Option(
        names = "--stdin",
        description = "Read the file from standard input. Requires --lang."
    )
    boolean stdin;

    @Parameters(
        index = "0",
        arity = "0..1",
        description = "Source file to read. Options must come first.",
        paramLabel = "FILE"
    )
    Path file;

    @Inject
    ReadService readService;

    @Inject
    TrackingRepository tracking;

    public ReadCommand() {}

    public ReadCommand(ReadService readService, TrackingRepository tracking) {
        this.readService = readService;
        this.tracking = tracking;
    }

    @Override
    public Integer call() {
        long started = System.nanoTime();
        try {
            if (stdin && file != null) {
                System.err.println("condense read: use either FILE or --stdin, not both");
                return 1;
            }
            if (!stdin && file == null) {
                System.err.println("condense read: missing file. Example: condense read --level comments Src.java");
                return 1;
            }
            ReadLevel parsedLevel = ultraCompact ? ReadLevel.OUTLINE : ReadLevel.parse(level);
            ReadService.Request request = new ReadService.Request(
                file,
                stdin ? System.in.readAllBytes() : null,
                stdin,
                parsedLevel,
                lang,
                cwd(),
                root,
                maxBytes
            );
            ReadService.Outcome outcome = readService.execute(request);
            if (!outcome.stderr().isBlank()) {
                System.err.println(outcome.stderr());
            }
            if (!outcome.ok()) {
                return outcome.exitCode();
            }
            if ("json".equalsIgnoreCase(format)) {
                System.out.println(Mappers.JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(outcome.report()));
            } else {
                System.out.print(outcome.stdout());
                if (!outcome.stdout().isEmpty() && !outcome.stdout().endsWith("\n")) {
                    System.out.println();
                }
            }
            System.out.flush();
            record(outcome, parsedLevel, started);
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("condense read: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("condense read: error: " + e.getMessage());
            return 1;
        } finally {
            if (tracking != null) {
                tracking.close();
            }
        }
    }

    private void record(ReadService.Outcome outcome, ReadLevel parsedLevel, long startedNanos) {
        if (tracking == null || outcome == null || !outcome.ok()) {
            return;
        }
        try {
            String display = stdin
                ? "<stdin>"
                : (file == null ? "" : file.toString());
            String command = "read --level " + parsedLevel.token() + " " + display;
            tracking.insert(
                command,
                ProjectFingerprint.ofCurrentDir(),
                System.getProperty("user.dir"),
                outcome.rawTokens(),
                outcome.outTokens(),
                Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L)
            );
        } catch (Exception ignored) {
            // fail-open — the file was already printed
        }
    }

    private static Path cwd() {
        return Path.of(System.getProperty("user.dir", "."));
    }
}
