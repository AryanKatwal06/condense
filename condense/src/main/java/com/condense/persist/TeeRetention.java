package com.condense.persist;

import com.condense.core.SafePathValidator;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Bounded, path-safe deletion of expired files under {@code {dataDir}/tee}.
 *
 * <p>Never follows symlinks. Never walks outside the tee directory.
 * At most {@link RetentionPolicy#TEE_SWEEP_LIMIT} files are unlinked per call.
 */
public final class TeeRetention {

    private static final Logger log = Logger.getLogger(TeeRetention.class);

    public record SweepResult(int deleted, int remainingOld, int scanned, Long oldestMtimeEpoch) {
        public static SweepResult empty() {
            return new SweepResult(0, 0, 0, null);
        }
    }

    private TeeRetention() {}

    public static SweepResult prune(Path dataDir) {
        if (dataDir == null) {
            return SweepResult.empty();
        }
        Path teeDir = dataDir.resolve("tee");
        if (!Files.exists(teeDir, LinkOption.NOFOLLOW_LINKS)) {
            return SweepResult.empty();
        }
        if (Files.isSymbolicLink(teeDir) || !Files.isDirectory(teeDir, LinkOption.NOFOLLOW_LINKS)) {
            log.warnf("Skipping tee retention; %s is not a regular directory", teeDir);
            return SweepResult.empty();
        }
        SafePathValidator.ContainmentResult dirContain = SafePathValidator.contain(teeDir, dataDir);
        if (!dirContain.contained()) {
            log.warnf("Skipping tee retention; %s is not contained in %s", teeDir, dataDir);
            return SweepResult.empty();
        }

        long cutoffMillis = RetentionPolicy.cutoffEpochSeconds() * 1000L;
        int deleted = 0;
        int remainingOld = 0;
        int scanned = 0;
        Long oldestEpoch = null;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(teeDir)) {
            for (Path entry : stream) {
                scanned++;
                if (Files.isSymbolicLink(entry) || Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                SafePathValidator.ContainmentResult fileContain = SafePathValidator.contain(entry, teeDir);
                if (!fileContain.contained()) {
                    continue;
                }
                long mtime = Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toMillis();
                long mtimeEpoch = mtime / 1000L;
                if (oldestEpoch == null || mtimeEpoch < oldestEpoch) {
                    oldestEpoch = mtimeEpoch;
                }
                if (mtime >= cutoffMillis) {
                    continue;
                }
                if (deleted < RetentionPolicy.TEE_SWEEP_LIMIT) {
                    Files.delete(entry);
                    deleted++;
                } else {
                    remainingOld++;
                }
            }
        } catch (IOException e) {
            log.warnf("Tee retention sweep failed under %s: %s", teeDir, e.getMessage());
        }
        return new SweepResult(deleted, remainingOld, scanned, oldestEpoch);
    }
}
