package com.condense.filter.pipeline;

import com.condense.filter.strategy.BoundedRegex;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-filter fidelity check for failure output and unified patches.
 *
 * <p>Compression is allowed to shorten output, but it must not silently delete
 * the evidence a caller needs: compiler diagnostics, stack traces, test
 * failures, and the structural lines of a unified diff. When a check fails the
 * caller falls back to the raw output and records
 * {@link FilterIncident#applyFallback}.
 */
public final class FidelityGuard {

    public static final FidelityGuard INSTANCE = new FidelityGuard();

    /** Prefix recorded in the fallback incident detail. */
    public static final String INCIDENT_DETAIL_PREFIX = "fidelity_guard_failures";

    private static final Pattern COMPILER_ERROR = Pattern.compile(
        "^\\s*(.+?):(\\d+)(?::\\d+)?:\\s*(?:fatal\\s+)?error\\b.*$",
        Pattern.CASE_INSENSITIVE);

    /** Interpreted-language linters also use {@code path:line: error}, but are not compilers. */
    private static final Pattern COMPILED_SOURCE = Pattern.compile(
        "(?i).+\\.(?:java|rs|c|h|cc|cpp|cxx|hpp|cs|go|kt|kts|swift|scala|m|mm|dart|zig|ex|exs|erl|hrl|ml|fs|fsx|jl|lua)$");

    private static final Pattern STACK_FRAME = Pattern.compile(
        "^\\s+at\\s+[\\w.$<>]+\\(.*\\)\\s*$");

    private static final Pattern TEST_TALLY = Pattern.compile(
        "(?:Failures|Errors):\\s*[1-9]\\d*|\\b[1-9]\\d*\\s+(?:tests?\\s+)?failed\\b",
        Pattern.CASE_INSENSITIVE);

    private static final List<String> EXCEPTION_MARKERS = List.of(
        "Traceback (most recent call last)",
        "Exception in thread",
        "Caused by:",
        "panicked at");

    private static final List<String> TEST_MARKERS = List.of(
        "FAILED",
        "FAIL:",
        "--- FAIL",
        "AssertionError",
        "\u2717",
        "\u2715");

    /** Commands whose compact output is an intentional summary, not a loss. */
    private static final Pattern LENIENT_COMMAND = Pattern.compile(
        "(?i)^\\s*git\\s+(?:status|log|push|ls)\\b|(?:^|\\s)--stat\\b");

    private static final int MAX_REPORTED = 5;

    private FidelityGuard() {}

    public record Verdict(boolean passed, List<String> failures) {
        public Verdict {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public static Verdict pass() {
            return new Verdict(true, List.of());
        }

        public static Verdict fail(List<String> failures) {
            return new Verdict(false, failures);
        }

        public String detail() {
            if (passed || failures.isEmpty()) {
                return INCIDENT_DETAIL_PREFIX;
            }
            List<String> bounded = failures.size() <= MAX_REPORTED
                ? failures
                : failures.subList(0, MAX_REPORTED);
            return INCIDENT_DETAIL_PREFIX + " " + String.join("; ", bounded);
        }
    }

    /**
     * @param command  proxied command string, used for the lenient summary whitelist
     * @param exitCode child exit code; non-zero enables the strict failure checks
     * @param raw      input handed to the pipeline
     * @param filtered pipeline output before provenance stamping
     */
    public Verdict check(String command, int exitCode, String raw, String filtered) {
        String source = raw == null ? "" : raw;
        String output = filtered == null ? "" : filtered;
        List<String> failures = new ArrayList<>();

        if (!source.isBlank() && output.isBlank()) {
            failures.add("filtered output is empty");
            return Verdict.fail(failures);
        }
        if (source.equals(output)) {
            return Verdict.pass();
        }

        checkUnifiedDiff(source, output, failures);

        if (exitCode != 0 && !isLenient(command)) {
            checkException(source, output, failures);
            checkCompilerDiagnostics(source, output, failures);
            checkTestFailures(source, output, failures);
        }
        return failures.isEmpty() ? Verdict.pass() : Verdict.fail(failures);
    }

    private void checkUnifiedDiff(String raw, String filtered, List<String> failures) {
        if (!hasLineStart(raw, "diff --git ")) {
            return;
        }
        if (!hasLineStart(filtered, "diff --git ")) {
            failures.add("unified diff file header dropped");
        }
        if (hasLineStart(raw, "--- ") && !hasLineStart(filtered, "--- ")) {
            failures.add("unified diff --- header dropped");
        }
        if (hasLineStart(raw, "+++ ") && !hasLineStart(filtered, "+++ ")) {
            failures.add("unified diff +++ header dropped");
        }
        if (hasLineStart(raw, "@@") && !hasLineStart(filtered, "@@")) {
            failures.add("unified diff hunk header dropped");
        }
        boolean rawBody = hasChangeLine(raw);
        boolean keptBody = hasChangeLine(filtered);
        if (rawBody && !keptBody) {
            failures.add("unified diff change lines dropped");
        }
    }

    private void checkException(String raw, String filtered, List<String> failures) {
        for (String marker : EXCEPTION_MARKERS) {
            if (raw.contains(marker) && !filtered.contains(marker)) {
                failures.add("exception marker dropped " + marker);
            }
        }
        if (hasStackFrame(raw) && !hasStackFrame(filtered)
            && EXCEPTION_MARKERS.stream().noneMatch(filtered::contains)) {
            failures.add("stack frames dropped");
        }
    }

    private void checkCompilerDiagnostics(String raw, String filtered, List<String> failures) {
        List<String> locations = new ArrayList<>();
        for (String line : raw.lines().toList()) {
            Matcher matcher = BoundedRegex.matcher(COMPILER_ERROR, line);
            if (matcher.matches() && BoundedRegex.matcher(COMPILED_SOURCE, matcher.group(1)).matches()) {
                locations.add(matcher.group(1) + ":" + matcher.group(2));
                if (locations.size() >= 3) {
                    break;
                }
            }
        }
        for (String location : locations) {
            if (!filtered.contains(location)) {
                failures.add("compiler diagnostic dropped " + location);
            }
        }
    }

    private void checkTestFailures(String raw, String filtered, List<String> failures) {
        boolean rawFailure = hasTestFailure(raw);
        if (!rawFailure) {
            return;
        }
        if (!hasTestFailure(filtered) && !hasPlainFailureWord(filtered)) {
            failures.add("test failure markers dropped");
        }
    }

    static boolean hasTestFailure(String text) {
        for (String line : text.lines().toList()) {
            String trimmed = line.strip();
            for (String marker : TEST_MARKERS) {
                if (trimmed.contains(marker)) {
                    return true;
                }
            }
            if (BoundedRegex.find(TEST_TALLY, trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPlainFailureWord(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("failed") || lower.contains("failure") || lower.contains("error");
    }

    private static boolean hasStackFrame(String text) {
        for (String line : text.lines().toList()) {
            if (BoundedRegex.matcher(STACK_FRAME, line).matches()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLineStart(String text, String prefix) {
        for (String line : text.lines().toList()) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasChangeLine(String text) {
        for (String line : text.lines().toList()) {
            if ((line.startsWith("+") && !line.startsWith("+++"))
                || (line.startsWith("-") && !line.startsWith("---"))) {
                return true;
            }
        }
        return false;
    }

    static boolean isLenient(String command) {
        return command != null && BoundedRegex.find(LENIENT_COMMAND, command);
    }
}
