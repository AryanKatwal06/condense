package com.condense.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import com.condense.persist.AtomicFile;
import com.condense.persist.CondenseClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Saves raw command output to a file when the tee system is active.
 *
 * <p>When a command fails, or when {@code tee.mode = "omissions"} is configured and
 * the filter dropped content (explicit omission markers or at least a 2x token
 * reduction), the AI may need the complete unfiltered output. Rather than
 * re-executing the command, Condense saves the raw output to a file and appends the
 * file path to the filtered output. The AI can then read that file directly, for
 * example via the MCP {@code expand} tool.
 *
 * <p>Tee files are stored at:
 * {@code {dataDir}/tee/{command-hash}-{unix-timestamp}.txt}
 *
 * <p>The command hash is the first 8 hex characters of the SHA-256 of the
 * command string, giving stable filenames for the same command across runs.
 *
 * <p>Files never exceed {@value #MAX_TEE_FILE_BYTES} bytes and carry an explicit
 * truncation marker when they did. Where POSIX permissions are available the file
 * is restricted to owner read/write ({@code 0600}). At most {@value #MAX_TEE_FILES}
 * tee files are kept; the oldest by modification time are deleted first.
 */
@ApplicationScoped
public class TeeWriter {

    private static final Logger log = Logger.getLogger(TeeWriter.class);

    public static final long MAX_TEE_FILE_BYTES = 5L * 1024 * 1024;
    public static final int MAX_TEE_FILES = 50;
    public static final String TRUNCATION_MARKER = "... (tee output truncated at 5 MiB) ...";
    private static final int TRUNCATION_RESERVE =
        TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length + 2;

    @Inject
    PlatformDirs platformDirs;

    @Inject
    ConfigLoader configLoader;

    private final AtomicFile atomic;

    public TeeWriter() {
        this.atomic = AtomicFile.SYSTEM;
    }

    public TeeWriter(PlatformDirs platformDirs, ConfigLoader configLoader) {
        this(platformDirs, configLoader, AtomicFile.SYSTEM);
    }

    public TeeWriter(PlatformDirs platformDirs, ConfigLoader configLoader, AtomicFile atomic) {
        this.platformDirs = platformDirs;
        this.configLoader = configLoader;
        this.atomic = atomic == null ? AtomicFile.SYSTEM : atomic;
    }

    /**
     * Possibly saves raw output to a tee file, based on config and exit code.
     *
     * <p>If saving occurs, returns the file path so the caller can append it to
     * the filtered output. If saving does not occur, returns {@code null}.
     *
     * @param command the command string for file naming and logging
     * @param result  the raw execution result whose output may be saved
     * @return the absolute path of the saved tee file, or {@code null} if not saved
     */
    public Path maybeDump(String command, ExecutionResult result) {
        return maybeDump(command, result, null);
    }

    /**
     * Possibly saves raw output to a tee file, based on config, exit code, and
     * whether filtering omitted content.
     *
     * <p>With {@link TeeMode#OMISSIONS} the raw output is saved when the command
     * failed, when the filtered output carries an explicit omission marker, or when
     * token filtering shrank the output by at least a factor of two.
     *
     * @param filtered the filtered result for this command, or {@code null} when unknown
     */
    public Path maybeDump(String command, ExecutionResult result, FilterResult filtered) {
        CondenseConfig config = configLoader.load();
        CondenseConfig.TeeConfig tee = config.tee();

        if (!tee.enabled()) return null;

        boolean shouldSave = switch (tee.mode()) {
            case ALWAYS   -> true;
            case FAILURES -> !result.succeeded();
            case OMISSIONS -> !result.succeeded() || filterOmittedContent(filtered);
            case NEVER    -> false;
        };

        if (!shouldSave) return null;

        return dump(command, result);
    }

    /**
     * True when the filter result indicates content was omitted: either an explicit
     * omission marker is present in the filtered output, or the token count was
     * reduced by at least a factor of two.
     */
    static boolean filterOmittedContent(FilterResult filtered) {
        if (filtered == null || !filtered.wasFiltered()) {
            return false;
        }
        if (hasOmissionMarker(filtered.output())) {
            return true;
        }
        return filtered.rawTokens() > 0 && filtered.outTokens() * 2L <= filtered.rawTokens();
    }

    /**
     * Matches the truncation markers emitted by condense stages. Every marker line
     * starts (optionally indented) with {@code "... ("} and ends with {@code "..."}.
     */
    static boolean hasOmissionMarker(String output) {
        if (output == null) {
            return false;
        }
        return output.lines()
            .map(String::trim)
            .anyMatch(line -> line.startsWith("... (")
                && line.contains("omitted")
                && line.endsWith("..."));
    }

    private Path dump(String command, ExecutionResult result) {
        try {
            Path teeDir = platformDirs.getDataDir().resolve("tee");
            String hash = ProjectFingerprint.of(command).substring(0, 8);
            long ts = CondenseClock.epochSeconds();
            String filename = hash + "-" + ts + ".txt";
            Path file = teeDir.resolve(filename);

            SafePathValidator.ContainmentResult containment =
                SafePathValidator.contain(file, teeDir);
            if (!containment.contained()) {
                log.warnf("Refusing to write tee file outside %s: %s", teeDir, containment.reason());
                return null;
            }

            // Reserve TRUNCATION_RESERVE bytes so a trailing truncation marker can be
            // written without exceeding MAX_TEE_FILE_BYTES.
            long[] budget = {MAX_TEE_FILE_BYTES - TRUNCATION_RESERVE};
            boolean[] truncated = {false};
            atomic.write(file, teeDir, out -> {
                budget[0] = writeUtf8(out, budget[0], "# condense tee dump\n");
                budget[0] = writeUtf8(out, budget[0], "# command: " + command + "\n");
                budget[0] = writeUtf8(out, budget[0], "# exit:    " + result.exitCode() + "\n");
                budget[0] = writeUtf8(out, budget[0], "# elapsed: " + result.durationMs() + "ms\n");
                budget[0] = writeUtf8(out, budget[0], "# timestamp: " + CondenseClock.instant() + "\n");
                budget[0] = writeUtf8(out, budget[0], "#\n");

                if (safeSize(result.stdoutFile()) > 0) {
                    budget[0] = writeUtf8(out, budget[0], "## stdout\n");
                    try (InputStream in = result.stdoutStream()) {
                        budget[0] = copyBounded(out, in, budget[0], truncated);
                    }
                    budget[0] = writeUtf8(out, budget[0], "\n");
                }
                if (safeSize(result.stderrFile()) > 0) {
                    budget[0] = writeUtf8(out, budget[0], "## stderr\n");
                    try (InputStream in = result.stderrStream()) {
                        budget[0] = copyBounded(out, in, budget[0], truncated);
                    }
                    budget[0] = writeUtf8(out, budget[0], "\n");
                }

                if (truncated[0]) {
                    writeUtf8Bounded(out, TRUNCATION_MARKER + "\n", TRUNCATION_RESERVE);
                }
            }, ".condense-tee-", ".tmp");

            restrictToOwner(file);
            rotateTeeFiles(teeDir);

            log.debugf("Tee file written: %s", file);
            return file;

        } catch (IOException e) {
            log.warnf("Failed to write tee file for '%s': %s", command, e.getMessage());
            return null;
        }
    }

    private static long safeSize(Path file) {
        if (file == null) {
            return 0;
        }
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long writeUtf8(OutputStream out, long remaining, String text) throws IOException {
        if (remaining <= 0) {
            return 0;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int take = (int) Math.min(bytes.length, remaining);
        out.write(bytes, 0, take);
        return remaining - take;
    }

    private static void writeUtf8Bounded(OutputStream out, String text, long maxBytes) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, (int) Math.min(bytes.length, maxBytes));
    }

    private static long copyBounded(OutputStream out, InputStream in, long budget, boolean[] truncated)
        throws IOException {
        byte[] chunk = new byte[8192];
        while (budget > 0) {
            int read = in.read(chunk, 0, (int) Math.min(chunk.length, budget));
            if (read == -1) {
                return budget;
            }
            out.write(chunk, 0, read);
            budget -= read;
        }
        if (in.read() != -1) {
            truncated[0] = true;
        }
        return budget;
    }

    /**
     * Sets owner read/write only. Silently skipped on non-POSIX file systems
     * (for example Windows).
     */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX file system - nothing to restrict.
        } catch (IOException e) {
            log.debugf("Could not set 0600 on tee file %s: %s", file, e.getMessage());
        }
    }

    /**
     * Keeps at most {@link #MAX_TEE_FILES} regular {@code *.txt} files in the tee
     * directory, deleting the oldest by modification time first.
     */
    void rotateTeeFiles(Path teeDir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(teeDir, "*.txt")) {
            List<Path> files = new ArrayList<>();
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    files.add(p);
                }
            }
            if (files.size() <= MAX_TEE_FILES) {
                return;
            }
            files.sort(Comparator
                .comparingLong(this::lastModifiedQuietly)
                .thenComparing(Path::toString));
            int remove = files.size() - MAX_TEE_FILES;
            for (int i = 0; i < remove; i++) {
                Path victim = files.get(i);
                if (!SafePathValidator.contain(victim, teeDir).contained()) {
                    continue; // guard against symlink escapes during rotation
                }
                try {
                    Files.deleteIfExists(victim);
                } catch (IOException e) {
                    log.warnf("Could not rotate old tee file %s: %s", victim, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warnf("Could not rotate tee files in %s: %s", teeDir, e.getMessage());
        }
    }

    private long lastModifiedQuietly(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

}
