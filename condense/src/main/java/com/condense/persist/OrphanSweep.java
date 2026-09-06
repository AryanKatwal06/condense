package com.condense.persist;

import com.condense.core.SafePathValidator;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded unlink of known Condense temp files under config and data dirs.
 * Never follows symlinks. Never deletes {@code condense.db-wal} / {@code -shm}.
 */
public final class OrphanSweep {

    public static final int LIMIT = RetentionPolicy.TEE_SWEEP_LIMIT;

    public record Result(int deleted, int remaining, List<String> names) {
        public static Result empty() {
            return new Result(0, 0, List.of());
        }
    }

    private OrphanSweep() {}

    public static Result sweep(Path configDir, Path dataDir) {
        List<String> found = new ArrayList<>();
        int deleted = 0;
        int remaining = 0;
        deleted += sweepDir(configDir, configDir, found, LIMIT);
        int budget = Math.max(0, LIMIT - deleted);
        deleted += sweepDir(dataDir, dataDir, found, budget);
        remaining = Math.max(0, found.size() - deleted);
        return new Result(deleted, remaining, List.copyOf(found));
    }

    private static int sweepDir(Path dir, Path containedBy, List<String> found, int budget) {
        if (dir == null || budget <= 0 || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        if (Files.isSymbolicLink(dir)) {
            return 0;
        }
        SafePathValidator.ContainmentResult dirContain = SafePathValidator.contain(dir, containedBy);
        if (!dirContain.contained()) {
            return 0;
        }
        int deleted = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (deleted >= budget) {
                    break;
                }
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = entry.getFileName() == null ? "" : entry.getFileName().toString();
                if (!SafePathValidator.isKnownCondenseTemp(name)) {
                    continue;
                }
                SafePathValidator.ContainmentResult fileContain = SafePathValidator.contain(entry, containedBy);
                if (!fileContain.contained()) {
                    continue;
                }
                found.add(name);
                try {
                    Files.deleteIfExists(entry);
                    deleted++;
                } catch (IOException ignored) {
                    // fail-open
                }
            }
        } catch (IOException ignored) {
        }
        return deleted;
    }
}
