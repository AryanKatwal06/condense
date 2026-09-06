package com.condense.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Closed, compile-time argv policy for .NET sidecar artifacts. Never injects a
 * TRX logger. Failure launches the original argv.
 */
public final class SidecarArgvPolicy {

    public static final String SIDECAR_PREFIX = "condense-dotnet-";
    public static final String BINLOG_NAME = "msbuild.binlog";
    public static final String FORMAT_REPORT_NAME = "format-report.json";
    public static final int MAX_TRX_FILES = 8;
    public static final long MAX_TRX_BYTES = 8L * 1024 * 1024;

    private static final List<Prefix> PREFIXES = List.of(
        new Prefix(List.of("dotnet", "msbuild"), Kind.BINLOG),
        new Prefix(List.of("dotnet", "build"), Kind.BINLOG),
        new Prefix(List.of("dotnet", "test"), Kind.BINLOG),
        new Prefix(List.of("dotnet", "restore"), Kind.BINLOG),
        new Prefix(List.of("dotnet", "format"), Kind.FORMAT),
        new Prefix(List.of("msbuild"), Kind.BINLOG)
    );

    public enum Kind {
        BINLOG,
        FORMAT,
        NONE
    }

    public record Decision(
        List<String> originalArgs,
        List<String> launchArgs,
        Path sidecarDir,
        List<Path> namedArtifacts,
        boolean injected,
        Kind kind
    ) {
        public Decision {
            originalArgs = originalArgs == null ? List.of() : List.copyOf(originalArgs);
            launchArgs = launchArgs == null ? originalArgs : List.copyOf(launchArgs);
            namedArtifacts = namedArtifacts == null ? List.of() : List.copyOf(namedArtifacts);
            kind = kind == null ? Kind.NONE : kind;
        }
    }

    private record Prefix(List<String> tokens, Kind kind) {}

    private SidecarArgvPolicy() {}

    public static Decision prepare(List<String> original) {
        try {
            return prepare0(original == null ? List.of() : original);
        } catch (Exception ignored) {
            List<String> safe = original == null ? List.of() : List.copyOf(original);
            return new Decision(safe, safe, null, List.of(), false, Kind.NONE);
        }
    }

    public static List<Path> collect(Decision decision, Path cwd) {
        List<Path> found = new ArrayList<>();
        if (decision == null) {
            return List.of();
        }
        Path workspace = SafePathValidator.resolveWorkspaceRoot(
            cwd == null ? Path.of(System.getProperty("user.dir", ".")) : cwd);
        if (decision.sidecarDir() != null && Files.isDirectory(decision.sidecarDir(), LinkOption.NOFOLLOW_LINKS)) {
            addIfReadable(found, decision.sidecarDir().resolve(BINLOG_NAME), decision.sidecarDir());
            addIfReadable(found, decision.sidecarDir().resolve(FORMAT_REPORT_NAME), decision.sidecarDir());
        }
        for (Path named : decision.namedArtifacts()) {
            addIfReadable(found, named, workspace, decision.sidecarDir());
        }
        discoverTrx(found, decision.originalArgs(), workspace);
        return List.copyOf(found);
    }

    public static void cleanup(Decision decision) {
        if (decision == null || decision.sidecarDir() == null) {
            return;
        }
        try {
            Path dir = decision.sidecarDir();
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    if (Files.isSymbolicLink(entry)) {
                        continue;
                    }
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        Files.deleteIfExists(entry);
                    }
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // fail-open
        }
    }

    private static Decision prepare0(List<String> original) throws IOException {
        List<String> args = List.copyOf(original);
        Prefix prefix = match(args);
        if (prefix == null || hasHelp(args)) {
            return new Decision(args, args, null, List.of(), false, Kind.NONE);
        }
        Scan scan = scan(args, prefix.kind());
        if (scan.collision) {
            return new Decision(args, args, null, scan.named, false, prefix.kind());
        }
        Path sidecar = Files.createTempDirectory(SIDECAR_PREFIX);
        List<String> launch = new ArrayList<>();
        int dashDash = indexOfDashDash(args);
        int insertAt = dashDash < 0 ? args.size() : dashDash;
        for (int i = 0; i < insertAt; i++) {
            launch.add(args.get(i));
        }
        if (prefix.kind() == Kind.FORMAT) {
            launch.add("--report");
            launch.add(sidecar.toString());
        } else {
            launch.add("-bl:" + sidecar.resolve(BINLOG_NAME));
        }
        if (dashDash >= 0) {
            for (int i = dashDash; i < args.size(); i++) {
                launch.add(args.get(i));
            }
        }
        return new Decision(args, launch, sidecar, scan.named, true, prefix.kind());
    }

    private static Prefix match(List<String> args) {
        if (args.isEmpty()) {
            return null;
        }
        List<Prefix> found = new ArrayList<>();
        for (Prefix prefix : PREFIXES) {
            if (startsWith(args, prefix.tokens())) {
                found.add(prefix);
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        found.sort(Comparator.comparingInt((Prefix p) -> p.tokens().size()).reversed());
        return found.getFirst();
    }

    private static boolean startsWith(List<String> args, List<String> tokens) {
        if (args.size() < tokens.size()) {
            return false;
        }
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).equalsIgnoreCase(args.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasHelp(List<String> args) {
        int end = indexOfDashDash(args);
        if (end < 0) {
            end = args.size();
        }
        for (int i = 0; i < end; i++) {
            String token = args.get(i);
            if (token == null) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if ("--help".equals(lower) || "-h".equals(lower) || "-?".equals(lower) || "/?".equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOfDashDash(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            if ("--".equals(args.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static final class Scan {
        private boolean collision;
        private final List<Path> named = new ArrayList<>();
    }

    private static Scan scan(List<String> args, Kind kind) {
        Scan scan = new Scan();
        int end = indexOfDashDash(args);
        if (end < 0) {
            end = args.size();
        }
        for (int i = 0; i < end; i++) {
            String token = args.get(i);
            if (token == null) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (kind == Kind.FORMAT) {
                if ("--report".equals(lower)) {
                    scan.collision = true;
                    addNamed(scan, nextValue(args, i, end));
                } else if (lower.startsWith("--report=")) {
                    scan.collision = true;
                    addNamed(scan, token.substring("--report=".length()));
                }
                continue;
            }
            if (isBareBinlogFlag(lower)) {
                scan.collision = true;
                addNamed(scan, nextValue(args, i, end));
            } else if (lower.startsWith("-bl:") || lower.startsWith("/bl:")) {
                scan.collision = true;
                addNamed(scan, token.substring(4));
            } else if (lower.startsWith("--binarylogger:") || lower.startsWith("--binarylogger=")) {
                scan.collision = true;
                addNamed(scan, token.substring(token.indexOf(':') >= 0 && lower.contains(":")
                    ? token.indexOf(':') + 1
                    : token.indexOf('=') + 1));
            } else if (looksLikeLoggerTrx(token) || looksLikeReportTrx(lower)) {
                addNamed(scan, trxPathFrom(token, args, i, end));
            } else if ("--results-directory".equals(lower) || "-r".equals(lower) && looksLikeResultsDir(args, i)) {
                addNamed(scan, nextValue(args, i, end));
            } else if (lower.startsWith("--results-directory=")) {
                addNamed(scan, token.substring("--results-directory=".length()));
            }
        }
        return scan;
    }

    private static boolean isBareBinlogFlag(String lower) {
        return "-bl".equals(lower)
            || "/bl".equals(lower)
            || "--binarylogger".equals(lower);
    }

    private static boolean looksLikeLoggerTrx(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.contains("trx") && (lower.contains("logger") || lower.contains("logfilename"));
    }

    private static boolean looksLikeReportTrx(String lower) {
        return lower.startsWith("--report-trx");
    }

    private static boolean looksLikeResultsDir(List<String> args, int i) {
        return i + 1 < args.size() && args.get(i + 1) != null && !args.get(i + 1).startsWith("-");
    }

    private static String nextValue(List<String> args, int i, int end) {
        if (i + 1 >= end) {
            return null;
        }
        String next = args.get(i + 1);
        if (next == null || next.startsWith("-") || next.startsWith("/")) {
            return null;
        }
        return next;
    }

    private static String trxPathFrom(String token, List<String> args, int i, int end) {
        String lower = token.toLowerCase(Locale.ROOT);
        int logFile = lower.indexOf("logfilename=");
        if (logFile >= 0) {
            return token.substring(logFile + "logfilename=".length());
        }
        if (lower.startsWith("--report-trx-filename")) {
            if (lower.startsWith("--report-trx-filename=")) {
                return token.substring("--report-trx-filename=".length());
            }
            return nextValue(args, i, end);
        }
        return nextValue(args, i, end);
    }

    private static void addNamed(Scan scan, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            scan.named.add(Path.of(raw));
        } catch (Exception ignored) {
            // fail-open
        }
    }

    private static void addIfReadable(List<Path> found, Path file, Path... roots) {
        if (file == null || found.contains(file)) {
            return;
        }
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            Path containRoot = Files.isRegularFile(root) ? root.getParent() : root;
            if (containRoot == null) {
                continue;
            }
            if (SafePathValidator.containReadable(file, containRoot).contained()) {
                found.add(file);
                return;
            }
        }
    }

    private static void discoverTrx(List<Path> found, List<String> args, Path workspace) {
        Path results = resultsDirectory(args, workspace);
        if (results == null) {
            results = workspace.resolve("TestResults");
        }
        if (!Files.isDirectory(results, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!SafePathValidator.contain(results, workspace).contained()) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(results, "*.trx")) {
            int added = 0;
            for (Path entry : stream) {
                if (added >= MAX_TRX_FILES) {
                    break;
                }
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    if (Files.size(entry) > MAX_TRX_BYTES) {
                        continue;
                    }
                } catch (IOException ignored) {
                    continue;
                }
                if (SafePathValidator.containReadable(entry, workspace).contained() && !found.contains(entry)) {
                    found.add(entry);
                    added++;
                }
            }
        } catch (IOException ignored) {
            // fail-open
        }
    }

    private static Path resultsDirectory(List<String> args, Path workspace) {
        int end = indexOfDashDash(args);
        if (end < 0) {
            end = args.size();
        }
        for (int i = 0; i < end; i++) {
            String token = args.get(i);
            if (token == null) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            String raw = null;
            if ("--results-directory".equals(lower)) {
                raw = nextValue(args, i, end);
            } else if (lower.startsWith("--results-directory=")) {
                raw = token.substring("--results-directory=".length());
            }
            if (raw != null && !raw.isBlank()) {
                Path dir = Path.of(raw);
                if (!dir.isAbsolute()) {
                    dir = workspace.resolve(dir);
                }
                return dir;
            }
        }
        return null;
    }
}
