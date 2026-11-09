package com.condense.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Shared, path-confined line reader for CLI and MCP tee recovery. */
public final class TeeFileReader {

    public static final int MAX_EXPAND_LINES = 500;
    private static final int MAX_CONTENT_BYTES = 32 * 1024;

    private TeeFileReader() {}

    public record Result(boolean ok, String error, Path file, int startLine, int endLine,
                         int totalLines, int linesReturned, boolean truncated, String content) {
        static Result failure(String error) {
            return new Result(false, error, null, 0, 0, 0, 0, false, "");
        }
    }

    /** Reads an inclusive, 1-based line range while retaining no more than the selected text. */
    public static Result read(Path requested, Path dataDir, Integer requestedStart, Integer requestedEnd) {
        if (requested == null || dataDir == null) {
            return Result.failure("tee file path is required");
        }
        int start = requestedStart == null ? 1 : Math.max(1, requestedStart);
        long defaultEnd = (long) start + 99;
        int end = requestedEnd == null ? (int) Math.min(Integer.MAX_VALUE, defaultEnd)
            : Math.max(start, requestedEnd);
        if ((long) end - start + 1 > MAX_EXPAND_LINES) {
            end = (int) Math.min(Integer.MAX_VALUE, (long) start + MAX_EXPAND_LINES - 1);
        }

        try {
            Path teeDir = dataDir.toAbsolutePath().normalize().resolve("tee");
            if (Files.isSymbolicLink(teeDir) || !Files.isDirectory(teeDir, LinkOption.NOFOLLOW_LINKS)) {
                return Result.failure("tee directory is missing or is a symlink");
            }
            Path canonicalTeeDir = teeDir.toRealPath();

            Path candidate = requested;
            if (!candidate.isAbsolute() && candidate.getNameCount() == 1) {
                candidate = canonicalTeeDir.resolve(candidate);
            } else if (!candidate.isAbsolute()) {
                candidate = Path.of(System.getProperty("user.dir", ".")).resolve(candidate);
            }
            for (Path segment : candidate) {
                if ("..".equals(segment.toString())) {
                    return Result.failure("parent traversal is not allowed");
                }
            }
            candidate = candidate.toAbsolutePath().normalize();
            if (!candidate.startsWith(canonicalTeeDir)) {
                return Result.failure("tee file must be inside the condense tee directory");
            }
            if (Files.isSymbolicLink(candidate)) {
                return Result.failure("symlink tee files are not allowed");
            }
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Result.failure("tee file does not exist or is not a regular file");
            }
            String name = candidate.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (!(name.endsWith(".txt") || name.endsWith(".log"))) {
                return Result.failure("tee file extension must be .txt or .log");
            }
            Path realFile = candidate.toRealPath();
            if (!realFile.startsWith(canonicalTeeDir)) {
                return Result.failure("tee file resolves outside the condense tee directory");
            }

            StringBuilder content = new StringBuilder();
            int total = 0;
            int returned = 0;
            int contentBytes = 0;
            int actualEnd = start - 1;
            boolean truncated = false;
            try (BufferedReader reader = Files.newBufferedReader(realFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    total++;
                    if (total < start || total > end) {
                        continue;
                    }
                    if (truncated) {
                        continue;
                    }
                    int lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (contentBytes + lineBytes > MAX_CONTENT_BYTES) {
                        truncated = true;
                        continue;
                    }
                    content.append(line).append('\n');
                    contentBytes += lineBytes;
                    returned++;
                    actualEnd = total;
                }
            }
            return new Result(true, null, realFile, start, actualEnd, total,
                returned, truncated, content.toString());
        } catch (IOException | SecurityException e) {
            return Result.failure("cannot read tee file");
        }
    }
}
