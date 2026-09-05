package com.condense.persist;

import com.condense.core.SafePathValidator;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Bounded deletion of expired third-party hook-config backups under
 * {@code {dataDir}/backups}. Files only — no nested directories — so purge
 * stays inside {@link SafePathValidator#KNOWN_CONDENSE_DIRECTORIES}.
 */
public final class BackupRetention {

    private static final Logger log = Logger.getLogger(BackupRetention.class);

    private BackupRetention() {}

    public static TeeRetention.SweepResult prune(Path dataDir) {
        if (dataDir == null) {
            return TeeRetention.SweepResult.empty();
        }
        Path backupDir = dataDir.resolve("backups");
        if (!Files.exists(backupDir, LinkOption.NOFOLLOW_LINKS)) {
            return TeeRetention.SweepResult.empty();
        }
        if (Files.isSymbolicLink(backupDir) || !Files.isDirectory(backupDir, LinkOption.NOFOLLOW_LINKS)) {
            log.warnf("Skipping backup retention; %s is not a regular directory", backupDir);
            return TeeRetention.SweepResult.empty();
        }
        SafePathValidator.ContainmentResult dirContain = SafePathValidator.contain(backupDir, dataDir);
        if (!dirContain.contained()) {
            log.warnf("Skipping backup retention; %s is not contained in %s", backupDir, dataDir);
            return TeeRetention.SweepResult.empty();
        }

        long cutoffMillis = RetentionPolicy.cutoffEpochSeconds() * 1000L;
        int deleted = 0;
        int remainingOld = 0;
        int scanned = 0;
        Long oldestEpoch = null;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir)) {
            for (Path entry : stream) {
                scanned++;
                if (Files.isSymbolicLink(entry) || Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                SafePathValidator.ContainmentResult fileContain = SafePathValidator.contain(entry, backupDir);
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
            log.warnf("Backup retention sweep failed under %s: %s", backupDir, e.getMessage());
        }
        return new TeeRetention.SweepResult(deleted, remainingOld, scanned, oldestEpoch);
    }
}
