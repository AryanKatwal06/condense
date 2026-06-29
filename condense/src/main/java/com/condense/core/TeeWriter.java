package com.condense.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Saves raw command output to a file when the tee system is active.
 *
 * <p>When a command fails (or when {@code tee.mode = "always"} is configured),
 * the AI may need to read the complete unfiltered output. Rather than re-executing
 * the command, Condense saves the raw output to a file and appends the file path to
 * the filtered output. The AI can then read that file directly.
 *
 * <p>Tee files are stored at:
 * {@code {dataDir}/tee/{command-hash}-{unix-timestamp}.txt}
 *
 * <p>The command hash is the first 8 hex characters of the SHA-256 of the
 * command string, giving stable filenames for the same command across runs.
 */
@ApplicationScoped
public class TeeWriter {

    private static final Logger log = Logger.getLogger(TeeWriter.class);

    @Inject
    PlatformDirs platformDirs;

    @Inject
    ConfigLoader configLoader;

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
        CondenseConfig config = configLoader.load();
        CondenseConfig.TeeConfig tee = config.tee();

        if (!tee.enabled()) return null;

        boolean shouldSave = switch (tee.mode()) {
            case ALWAYS   -> true;
            case FAILURES -> !result.succeeded();
            case NEVER    -> false;
        };

        if (!shouldSave) return null;

        return dump(command, result);
    }

    // ── private ──────────────────────────────────────────────────────────────

    private Path dump(String command, ExecutionResult result) {
        try {
            Path teeDir = platformDirs.getDataDir().resolve("tee");
            Files.createDirectories(teeDir);

            String hash = ProjectFingerprint.of(command).substring(0, 8);
            long ts = Instant.now().getEpochSecond();
            String filename = hash + "-" + ts + ".txt";
            Path file = teeDir.resolve(filename);

            try (java.io.OutputStream out = Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                 java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                writer.write("# condense tee dump\n");
                writer.write("# command: " + command + "\n");
                writer.write("# exit:    " + result.exitCode() + "\n");
                writer.write("# elapsed: " + result.durationMs() + "ms\n");
                writer.write("# timestamp: " + Instant.now() + "\n");
                writer.write("#\n");

                if (result.stdoutFile() != null && Files.size(result.stdoutFile()) > 0) {
                    writer.write("## stdout\n");
                    writer.flush();
                    try (java.io.InputStream in = result.stdoutStream()) {
                        in.transferTo(out);
                    }
                    writer.write("\n");
                }

                if (result.stderrFile() != null && Files.size(result.stderrFile()) > 0) {
                    writer.write("## stderr\n");
                    writer.flush();
                    try (java.io.InputStream in = result.stderrStream()) {
                        in.transferTo(out);
                    }
                    writer.write("\n");
                }
            }

            log.debugf("Tee file written: %s", file);
            return file;

        } catch (IOException e) {
            log.warnf("Failed to write tee file for '%s': %s", command, e.getMessage());
            return null;
        }
    }


}
