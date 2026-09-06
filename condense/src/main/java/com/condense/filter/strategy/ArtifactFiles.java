package com.condense.filter.strategy;

import com.condense.core.ExecutionResult;
import com.condense.core.SafePathValidator;
import com.condense.filter.pipeline.FilterContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves sidecar and user-named artifact paths from {@link ExecutionResult}
 * and the original argv. Never follows a TOML path key.
 */
final class ArtifactFiles {

    private ArtifactFiles() {}

    static List<Path> find(FilterContext context, String... suffixes) {
        List<Path> found = new ArrayList<>();
        Path workspace = SafePathValidator.resolveWorkspaceRoot(
            Path.of(System.getProperty("user.dir", ".")));
        if (context != null && context.result() != null) {
            ExecutionResult result = context.result();
            if (result.artifacts() != null) {
                for (Path artifact : result.artifacts()) {
                    add(found, artifact, suffixes, workspace);
                }
            }
        }
        if (context != null && context.argv() != null) {
            for (String token : context.argv()) {
                Path named = pathFromToken(token);
                add(found, named, suffixes, workspace);
            }
        }
        return List.copyOf(found);
    }

    private static Path pathFromToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.startsWith("-bl:") || lower.startsWith("/bl:")) {
            return pathOrNull(token.substring(4));
        }
        if (lower.startsWith("--binarylogger=") || lower.startsWith("--binarylogger:")) {
            return pathOrNull(token.substring(token.indexOf('=') >= 0 && lower.contains("=")
                ? token.indexOf('=') + 1
                : token.indexOf(':') + 1));
        }
        if (lower.startsWith("--report=")) {
            return pathOrNull(token.substring("--report=".length()));
        }
        if (lower.endsWith(".binlog") || lower.endsWith(".trx") || lower.endsWith(".json")) {
            return pathOrNull(token);
        }
        return null;
    }

    private static Path pathOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Path.of(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void add(List<Path> found, Path candidate, String[] suffixes, Path workspace) {
        if (candidate == null || found.contains(candidate)) {
            return;
        }
        if (Files.isDirectory(candidate)) {
            for (String suffix : suffixes) {
                Path nested = candidate.resolve(suffixName(suffix));
                addFile(found, nested, suffixes, workspace);
            }
            return;
        }
        addFile(found, candidate, suffixes, workspace);
    }

    private static String suffixName(String suffix) {
        if ("format-report.json".equals(suffix) || suffix.startsWith(".")) {
            return suffix;
        }
        return suffix.startsWith(".") ? suffix : suffix;
    }

    private static void addFile(List<Path> found, Path file, String[] suffixes, Path workspace) {
        if (file == null || !matches(file, suffixes) || found.contains(file)) {
            return;
        }
        Path parent = file.getParent();
        if (parent != null && SafePathValidator.containReadable(file, parent).contained()) {
            found.add(file);
            return;
        }
        if (SafePathValidator.containReadable(file, workspace).contained()) {
            found.add(file);
        }
    }

    private static boolean matches(Path file, String[] suffixes) {
        if (suffixes == null || suffixes.length == 0) {
            return true;
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            if (suffix == null) {
                continue;
            }
            String want = suffix.toLowerCase(Locale.ROOT);
            if (name.endsWith(want) || name.equals(want)) {
                return true;
            }
        }
        return false;
    }
}
