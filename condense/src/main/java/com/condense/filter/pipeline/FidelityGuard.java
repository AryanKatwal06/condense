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
 * failures, SQL errors, destructive infrastructure plans, security advisories,
 * and the structural lines of a unified diff. When a check fails the caller
 * falls back to the raw output and records
 * {@link FilterIncident#applyFallback}.
 *
 * <p>Inspection is bounded to the first and last 256 KiB of a large capture so a
 * diagnostic at the very end of a multi-megabyte build log is still checked
 * without scanning the whole document.
 */
public final class FidelityGuard {

    public static final FidelityGuard INSTANCE = new FidelityGuard();

    /** Inspection window in characters. Large inputs keep half from each end. */
    public static final int MAX_INSPECTION_CHARS = 512 * 1024;

    private static final int HALF_INSPECTION_CHARS = MAX_INSPECTION_CHARS / 2;

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

    private static final Pattern SQL_ERROR_LINE = Pattern.compile(
        "^(?:ERROR|FATAL|PANIC):\\s+(\\S.*)$");

    private static final Pattern TERRAFORM_REPLACE = Pattern.compile(
        "\\bmust be replaced\\b");

    private static final Pattern TERRAFORM_DESTROY_PLAN = Pattern.compile(
        "\\bPlan:\\s+\\d+ to add,\\s+\\d+ to change,\\s+[1-9]\\d* to destroy\\b");

    private static final Pattern CVE_ID = Pattern.compile(
        "\\bCVE-\\d{4}-\\d{4,7}\\b");

    private static final Pattern STRONG_FAILURE_SIGNAL = Pattern.compile(
        "(?:Exception in thread|Caused by:|Traceback \\(most recent call last\\)"
            + "|panicked at|\\bpanic:|\\bFAILED\\b|\\bFAIL:|AssertionError"
            + "|(?:Failures|Errors):\\s*[1-9])");

    private static final Pattern MISLEADING_SUCCESS = Pattern.compile(
        "(?i)^(?:ok|success|passed|all tests passed|0 errors?|no errors?"
            + "|build successful|build succeeded|no changes?)\\.?$");

    private static final Pattern DIFF_GIT_HEADER = Pattern.compile(
        "^diff --git a/(\\S+) b/(\\S+)$");

    private static final Pattern FILE_LINE_POINTER = Pattern.compile(
        "([\\w./\\\\-]+\\.(?:java|rs|py|ts|tsx|js|go|cs|cpp|cc|cxx|c|h|hpp"
            + "|kt|kts|rb|swift|scala|sql|tf)):(\\d+)");

    private static final Pattern DIFF_STAT_COMMAND = Pattern.compile(
        "(?i)(?:^|\\s)--(?:stat|shortstat|numstat)\\b");

    private static final Pattern GIT_DIFF_COMMAND = Pattern.compile(
        "(?i)\\bgit\\s+diff\\b");

    private static final List<String> EXCEPTION_MARKERS = List.of(
        "Traceback (most recent call last)",
        "Exception in thread",
        "Caused by:",
        "panicked at",
        "panic:");

    private static final List<String> TEST_MARKERS = List.of(
        "FAILED",
        "FAIL:",
        "--- FAIL",
        "AssertionError",
        "\u2717",
        "\u2715");

    /** Commands whose compact output is an intentional summary, not a loss. */
    private static final Pattern LENIENT_COMMAND = Pattern.compile(
        "(?i)^\\s*(?:git\\s+(?:status|log|push|branch|ls)|ls|tree)\\b|(?:^|\\s)--stat\\b");

    private static final int MAX_REPORTED = 5;
    private static final int MAX_SQL_REPORTED = 3;
    private static final int MAX_CVE_REPORTED = 3;
    private static final int SQL_MESSAGE_LIMIT = 80;

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
     * Bounded inspection input. Small captures are inspected whole. A large
     * capture contributes its first 256 KiB and its last 256 KiB, joined by a
     * newline, so both opening context and late diagnostics are visible.
     */
    public static String sampleHeadAndTail(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.length() <= MAX_INSPECTION_CHARS) {
            return raw;
        }
        return raw.substring(0, HALF_INSPECTION_CHARS)
            + "\n"
            + raw.substring(raw.length() - HALF_INSPECTION_CHARS);
    }

    /**
     * Extract the critical signals a finished output must not silently drop.
     * Used by tests and diagnostics; {@link #check} applies the same patterns
     * with command-aware leniency.
     */
    public static List<String> extractCriticalSignals(String command, String raw, int exitCode) {
        String sample = sampleHeadAndTail(raw);
        List<String> signals = new ArrayList<>();
        if (sample.isBlank()) {
            return signals;
        }

        if (isDiffCommand(command) && !isDiffSummaryCommand(command)) {
            int changes = 0;
            for (String line : sample.lines().toList()) {
                Matcher diff = BoundedRegex.matcher(DIFF_GIT_HEADER, line);
                if (diff.matches()) {
                    signals.add("diff_file:" + diff.group(2));
                    if (signals.size() >= 20) {
                        break;
                    }
                    continue;
                }
                if (changes < 5 && line.startsWith("+") && !line.startsWith("+++")) {
                    signals.add("diff_added:" + truncate(line.substring(1), SQL_MESSAGE_LIMIT));
                    changes++;
                } else if (changes < 5 && line.startsWith("-") && !line.startsWith("---")) {
                    signals.add("diff_removed:" + truncate(line.substring(1), SQL_MESSAGE_LIMIT));
                    changes++;
                }
            }
        }

        int sql = 0;
        for (String line : sample.lines().toList()) {
            Matcher match = BoundedRegex.matcher(SQL_ERROR_LINE, line);
            if (match.matches() && sql < MAX_SQL_REPORTED) {
                signals.add("sql:" + truncate(match.group(1).trim(), SQL_MESSAGE_LIMIT));
                sql++;
            }
        }

        if (BoundedRegex.find(TERRAFORM_REPLACE, sample)) {
            signals.add("terraform:replace");
        }
        if (BoundedRegex.find(TERRAFORM_DESTROY_PLAN, sample)) {
            signals.add("terraform:destroy");
        }

        Matcher cve = BoundedRegex.matcher(CVE_ID, sample);
        int advisories = 0;
        while (cve.find() && advisories < MAX_CVE_REPORTED) {
            signals.add("cve:" + cve.group());
            advisories++;
        }

        for (String marker : EXCEPTION_MARKERS) {
            if (sample.contains(marker)) {
                signals.add("exception:" + marker);
            }
        }

        for (String marker : TEST_MARKERS) {
            if (sample.contains(marker)) {
                signals.add("test_failure:" + marker);
            }
        }
        if (BoundedRegex.find(TEST_TALLY, sample)) {
            signals.add("test_tally");
        }

        Matcher pointer = BoundedRegex.matcher(FILE_LINE_POINTER, sample);
        int pointers = 0;
        while (pointer.find() && pointers < 5) {
            signals.add("pointer:" + pointer.group(1) + ":" + pointer.group(2));
            pointers++;
        }
        return signals;
    }

    /**
     * @param command  proxied command string, used for the lenient summary whitelist
     * @param exitCode child exit code; non-zero enables the strict failure checks
     * @param raw      input handed to the pipeline
     * @param filtered pipeline output before provenance stamping
     */
    public Verdict check(String command, int exitCode, String raw, String filtered) {
        String source = sampleHeadAndTail(raw);
        String output = sampleHeadAndTail(filtered);
        List<String> failures = new ArrayList<>();

        if (!source.isBlank() && output.isBlank()) {
            failures.add("filtered output is empty");
            return Verdict.fail(failures);
        }
        if (source.equals(output)) {
            return Verdict.pass();
        }

        checkUnifiedDiff(command, source, output, failures);
        checkInfrastructureSignals(source, output, failures);

        boolean strict = exitCode != 0 || hasStrongFailureSignal(source);
        if (strict && !isLenient(command)) {
            checkException(source, output, failures);
            checkCompilerDiagnostics(source, output, failures);
            checkTestFailures(source, output, failures);
        }

        if (exitCode != 0) {
            checkMisleadingSuccess(output, failures);
        }
        return failures.isEmpty() ? Verdict.pass() : Verdict.fail(failures);
    }

    private void checkUnifiedDiff(
            String command, String raw, String filtered, List<String> failures) {
        if (isDiffSummaryCommand(command)) {
            // A --stat/--shortstat/--numstat summary is intentionally count-only.
            return;
        }
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

    private void checkInfrastructureSignals(
            String raw, String filtered, List<String> failures) {
        int sql = 0;
        for (String line : raw.lines().toList()) {
            Matcher match = BoundedRegex.matcher(SQL_ERROR_LINE, line);
            if (!match.matches()) {
                continue;
            }
            String message = truncate(match.group(1).trim(), SQL_MESSAGE_LIMIT);
            if (!filtered.contains(message)) {
                failures.add("sql error message dropped " + message);
            }
            if (++sql >= MAX_SQL_REPORTED) {
                break;
            }
        }

        if (BoundedRegex.find(TERRAFORM_REPLACE, raw)
            && !BoundedRegex.find(TERRAFORM_REPLACE, filtered)) {
            failures.add("terraform replacement signal dropped");
        }
        if (BoundedRegex.find(TERRAFORM_DESTROY_PLAN, raw)
            && !BoundedRegex.find(TERRAFORM_DESTROY_PLAN, filtered)) {
            failures.add("terraform destructive plan summary dropped");
        }

        Matcher cve = BoundedRegex.matcher(CVE_ID, raw);
        int advisories = 0;
        while (cve.find() && advisories < MAX_CVE_REPORTED) {
            String id = cve.group();
            if (!filtered.contains(id)) {
                failures.add("security advisory dropped " + id);
            }
            advisories++;
        }
    }

    private void checkMisleadingSuccess(String output, List<String> failures) {
        String trimmed = output.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        if (BoundedRegex.matcher(MISLEADING_SUCCESS, trimmed).matches()) {
            failures.add("misleading clean summary on non-zero exit");
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

    private static boolean hasStrongFailureSignal(String text) {
        return text != null && BoundedRegex.find(STRONG_FAILURE_SIGNAL, text);
    }

    private static boolean isDiffCommand(String command) {
        return command != null && BoundedRegex.find(GIT_DIFF_COMMAND, command);
    }

    static boolean isDiffSummaryCommand(String command) {
        return command != null && BoundedRegex.find(DIFF_STAT_COMMAND, command);
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

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
