package com.condense.hooks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Copies an existing third-party agent config before Condense merges into it.
 * Fail-closed: callers must not overwrite the original if this throws.
 */
public final class HookBackup {

    private HookBackup() {}

    /**
     * @return destination path, or {@code null} when {@code source} does not exist
     */
    public static Path copyExisting(Path dataDir, HookTool tool, Path source) throws IOException {
        if (dataDir == null || tool == null || source == null) {
            throw new IOException("backup requires data directory, tool, and source");
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("refusing to backup non-regular file: " + source);
        }
        Path backupDir = dataDir.resolve("backups");
        Files.createDirectories(backupDir);
        String name = source.getFileName() == null ? "" : source.getFileName().toString();
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            ext = name.substring(dot);
        }
        String toolKey = tool.name().toLowerCase().replace('_', '-');
        Path dest = backupDir.resolve(toolKey + "-" + (System.currentTimeMillis() / 1000L) + ext);
        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
            dest = backupDir.resolve(toolKey + "-" + (System.currentTimeMillis() / 1000L) + "-" + System.nanoTime() + ext);
        }
        Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES);
        return dest;
    }
}
