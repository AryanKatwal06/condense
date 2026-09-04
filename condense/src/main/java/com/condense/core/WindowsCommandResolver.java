package com.condense.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Windows {@code CreateProcess} does not apply {@code PATHEXT}. Java
 * {@link ProcessBuilder} therefore cannot launch {@code pytest} when the
 * shim on {@code PATH} is {@code pytest.cmd}. Resolve the first match
 * ourselves, then wrap batch files in {@code cmd.exe /c}.
 */
final class WindowsCommandResolver {

    private static final String DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD";

    private WindowsCommandResolver() {}

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static boolean looksLikePath(String token) {
        return token.indexOf('/') >= 0 || token.indexOf('\\') >= 0;
    }

    static boolean isBatchFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".cmd") || name.endsWith(".bat");
    }

    static Optional<Path> resolve(String name, String pathEnv, String pathextEnv) {
        if (name == null || name.isBlank() || looksLikePath(name)) {
            return Optional.empty();
        }
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        List<String> extensions = parsePathext(pathextEnv);
        boolean nameHasDot = name.indexOf('.') >= 0;
        for (String dir : pathEnv.split(";")) {
            if (dir.isBlank()) {
                continue;
            }
            Path folder = Path.of(dir.trim());
            if (nameHasDot) {
                Path candidate = folder.resolve(name);
                Optional<Path> existing = existingFile(candidate);
                if (existing.isPresent()) {
                    return existing;
                }
                continue;
            }
            for (String ext : extensions) {
                Optional<Path> existing = existingFile(folder.resolve(name + ext));
                if (existing.isPresent()) {
                    return existing;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> existingFile(Path candidate) {
        if (!Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        try {
            return Optional.of(candidate.toRealPath());
        } catch (Exception e) {
            return Optional.of(candidate.toAbsolutePath().normalize());
        }
    }

    static List<String> rewrite(List<String> args, String pathEnv, String pathextEnv) {
        if (args == null || args.isEmpty()) {
            return args;
        }
        Optional<Path> resolved = resolve(args.get(0), pathEnv, pathextEnv);
        if (resolved.isEmpty()) {
            return args;
        }
        Path file = resolved.get();
        List<String> rewritten = new ArrayList<>(args.size() + 2);
        if (isBatchFile(file)) {
            rewritten.add("cmd.exe");
            rewritten.add("/c");
        }
        rewritten.add(file.toString());
        if (args.size() > 1) {
            rewritten.addAll(args.subList(1, args.size()));
        }
        return rewritten;
    }

    private static List<String> parsePathext(String pathextEnv) {
        String raw = (pathextEnv == null || pathextEnv.isBlank()) ? DEFAULT_PATHEXT : pathextEnv;
        List<String> extensions = new ArrayList<>();
        for (String part : raw.split(";")) {
            String ext = part.trim();
            if (ext.isEmpty()) {
                continue;
            }
            if (ext.charAt(0) != '.') {
                ext = "." + ext;
            }
            extensions.add(ext);
        }
        return extensions;
    }
}
