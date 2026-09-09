package com.condense.session;

import com.condense.core.SafePathValidator;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Common base class for session readers implementing bounded file discovery and safe reading.
 */
public abstract class AbstractSessionReader implements SessionReader {

    private static final Logger log = Logger.getLogger(AbstractSessionReader.class);
    protected static final long DEFAULT_MAX_FILE_BYTES = 10 * 1024 * 1024L; // 10 MB per file cap

    @Override
    public List<Path> discoverSessionFiles(Path baseDir, int maxAgeDays, int maxFiles) throws IOException {
        if (baseDir == null || !Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
            return List.of();
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(Math.max(1, maxAgeDays)));
        List<PathWithTime> candidates = new ArrayList<>();
        String extension = format().filePattern().replace("*", "");

        Files.walkFileTree(baseDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && file.getFileName().toString().endsWith(extension)) {
                    // Path confinement check
                    SafePathValidator.ContainmentResult res = SafePathValidator.contain(file, baseDir);
                    if (res.contained()) {
                        Instant modTime = attrs.lastModifiedTime().toInstant();
                        if (modTime.isAfter(cutoff)) {
                            candidates.add(new PathWithTime(file, modTime));
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.debugf("Skipping inaccessible session file %s: %s", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        candidates.sort(Comparator.comparing(PathWithTime::time).reversed());

        int limit = Math.min(candidates.size(), Math.max(1, maxFiles));
        List<Path> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            result.add(candidates.get(i).path());
        }
        return result;
    }

    /**
     * Reads up to {@code maxBytes} from a file as UTF-8 text, preventing OOM on huge files.
     */
    protected String readBoundedContent(Path file, long maxBytes) throws IOException {
        long effectiveMax = maxBytes > 0 ? maxBytes : DEFAULT_MAX_FILE_BYTES;
        long fileSize = Files.size(file);
        int bytesToRead = (int) Math.min(fileSize, effectiveMax);

        byte[] buffer = new byte[bytesToRead];
        try (InputStream in = Files.newInputStream(file)) {
            int read = 0;
            while (read < bytesToRead) {
                int count = in.read(buffer, read, bytesToRead - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    private record PathWithTime(Path path, Instant time) {}
}
