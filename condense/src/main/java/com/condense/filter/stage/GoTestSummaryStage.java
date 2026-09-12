package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.Mappers;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.ir.Document;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deep semantic filtering for Go test output.
 * <p>
 * Structurally parses {@code go test -json} event streams, associates failure diagnostics
 * and assertion diffs with failing tests, suppresses voluminous run and pass noise,
 * and preserves package build diagnostics. Also includes a structured fallback for plain
 * text {@code go test} output with per-test concurrent diagnostic association.
 */
@DeclarativeStage(aliases = {"go_test_summary", "go-test-summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class GoTestSummaryStage implements FilterStage {

    public static final GoTestSummaryStage INSTANCE = new GoTestSummaryStage();
    private static final ObjectMapper MAPPER = Mappers.JSON;

    private static final Pattern RUN_OR_CONT =
        Pattern.compile("^===\\s+(?:RUN|CONT)\\s+([^\\s]+).*$");
    private static final Pattern PAUSE_HEADER =
        Pattern.compile("^===\\s+PAUSE\\s+([^\\s]+).*$");
    private static final Pattern RUN_HEADER =
        Pattern.compile("^=== (?:RUN|PAUSE|CONT)\\s+.*$");
    private static final Pattern STATUS_HEADER =
        Pattern.compile("^--- (?:PASS|FAIL|SKIP):\\s+.*$");
    private static final Pattern PLAIN_FAIL_TEST =
        Pattern.compile("^--- FAIL:\\s+([^\\s(]+).*$");
    private static final Pattern PLAIN_PASS_OR_SKIP =
        Pattern.compile("^--- (?:PASS|SKIP):\\s+([^\\s(]+).*$");
    private static final Pattern PLAIN_FAIL_COLON =
        Pattern.compile("^FAIL:\\s+([^\\s(]+).*$");

    private GoTestSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        if (raw == null || raw.isBlank()) {
            return StageResult.continueWith(raw != null ? raw : "");
        }

        try {
            return parseGoTest(raw, context);
        } catch (Exception ignored) {
            // Defensive fail-open: any unexpected parsing issue preserves raw output
            return StageResult.continueWith(raw);
        }
    }

    private static StageResult parseGoTest(String raw, FilterContext context) {
        List<String> lines = raw.lines().toList();
        boolean hasJsonCandidate = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                hasJsonCandidate = true;
                break;
            }
        }

        if (!hasJsonCandidate) {
            return parsePlainGoTest(raw, context);
        }

        Map<String, List<String>> testBuffers = new HashMap<>();
        Map<String, List<String>> pkgBuffers = new HashMap<>();
        List<String> failedTestOrder = new ArrayList<>();
        Map<String, String> failedTestNames = new HashMap<>();
        Map<String, String> failedTestPkgs = new HashMap<>();
        Set<String> failedPackages = new LinkedHashSet<>();
        int passed = 0;
        int skipped = 0;
        int totalJsonEvents = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || !trimmed.startsWith("{")) {
                continue;
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(trimmed);
            } catch (Exception e) {
                continue;
            }
            totalJsonEvents++;
            String action = node.path("Action").asText("");
            String pkg = node.path("Package").asText("");
            String test = node.path("Test").asText("");
            String output = node.path("Output").asText(null);

            if ("output".equals(action) && output != null) {
                if (!test.isBlank()) {
                    String key = pkg + "\0" + test;
                    testBuffers.computeIfAbsent(key, k -> new ArrayList<>()).add(output);
                } else if (!pkg.isBlank()) {
                    pkgBuffers.computeIfAbsent(pkg, k -> new ArrayList<>()).add(output);
                }
            } else if ("pass".equals(action)) {
                if (!test.isBlank()) {
                    passed++;
                    String key = pkg + "\0" + test;
                    testBuffers.remove(key);
                }
            } else if ("skip".equals(action)) {
                if (!test.isBlank()) {
                    skipped++;
                    String key = pkg + "\0" + test;
                    testBuffers.remove(key);
                }
            } else if ("fail".equals(action)) {
                if (!test.isBlank()) {
                    String key = pkg + "\0" + test;
                    if (!failedTestNames.containsKey(key)) {
                        failedTestOrder.add(key);
                        failedTestNames.put(key, test);
                        failedTestPkgs.put(key, pkg);
                    }
                } else if (!pkg.isBlank()) {
                    failedPackages.add(pkg);
                }
            }
        }

        if (totalJsonEvents == 0) {
            return parsePlainGoTest(raw, context);
        }

        List<FailedTest> failedTests = new ArrayList<>();
        for (String key : failedTestOrder) {
            String testName = failedTestNames.get(key);
            String pkg = failedTestPkgs.get(key);
            List<String> rawDiags = testBuffers.get(key);
            List<String> cleanDiags = extractDiagnostics(rawDiags);
            failedTests.add(new FailedTest(pkg, testName, cleanDiags));
        }

        Set<String> pkgsWithFailedTests = new LinkedHashSet<>();
        for (FailedTest ft : failedTests) {
            pkgsWithFailedTests.add(ft.pkg());
        }

        List<PackageFailure> packageFailures = new ArrayList<>();
        for (String pkg : failedPackages) {
            List<String> rawPkgOutput = pkgBuffers.get(pkg);
            List<String> cleanPkgDiags = extractPackageDiagnostics(rawPkgOutput);
            boolean hasPkgDiags = !cleanPkgDiags.isEmpty();
            boolean hasNoFailedTests = !pkgsWithFailedTests.contains(pkg);
            if (hasPkgDiags || hasNoFailedTests) {
                String failureType = "package failed";
                String rawStr = rawPkgOutput != null ? String.join(" ", rawPkgOutput) : "";
                if (rawStr.contains("[build failed]") || rawStr.contains("build failed")) {
                    failureType = "build failed";
                } else if (rawStr.contains("panic:")) {
                    failureType = "panic";
                } else if (rawStr.contains("panic") || rawStr.contains("timed out")) {
                    failureType = "panic / timeout";
                }
                packageFailures.add(new PackageFailure(pkg, failureType, cleanPkgDiags));
            }
        }

        if (passed == 0 && skipped == 0 && failedTests.isEmpty() && packageFailures.isEmpty()) {
            return parsePlainGoTest(raw, context);
        }

        // Pure package failure scenario (e.g. build failure before any test executed)
        if (passed == 0 && skipped == 0 && failedTests.isEmpty() && !packageFailures.isEmpty()) {
            StringBuilder bsb = new StringBuilder();
            for (PackageFailure pf : packageFailures) {
                if (!bsb.isEmpty()) {
                    bsb.append('\n');
                }
                bsb.append("go test: ").append(pf.failureType()).append(" in ").append(pf.pkg()).append('\n');
                for (String d : pf.diagnostics()) {
                    bsb.append("  ").append(d).append('\n');
                }
            }
            publishJsonIr(context, failedTests, packageFailures, passed, skipped);
            return StageResult.continueWith(bsb.toString().stripTrailing());
        }

        StringBuilder sb = new StringBuilder();
        int totalFailures = failedTests.size() + packageFailures.size();
        if (totalFailures > 0) {
            sb.append("go test: ").append(totalFailures).append(" failure(s)\n");
            for (FailedTest failure : failedTests) {
                sb.append("  FAIL: ").append(failure.testName()).append('\n');
                for (String diag : failure.diagnostics()) {
                    sb.append("    ").append(diag).append('\n');
                }
            }
            for (PackageFailure pf : packageFailures) {
                sb.append("  FAIL: [").append(pf.failureType()).append("] ").append(pf.pkg()).append('\n');
                for (String diag : pf.diagnostics()) {
                    sb.append("    ").append(diag).append('\n');
                }
            }
        }
        sb.append("passed: ").append(passed);
        if (skipped > 0) {
            sb.append(" | skipped: ").append(skipped);
        }
        if (totalFailures > 0) {
            sb.append(" | failed: ").append(totalFailures);
        }

        publishJsonIr(context, failedTests, packageFailures, passed, skipped);
        return StageResult.continueWith(sb.toString().stripTrailing());
    }

    private static List<String> extractDiagnostics(List<String> rawLines) {
        if (rawLines == null || rawLines.isEmpty()) {
            return List.of();
        }
        List<String> diags = new ArrayList<>();
        for (String chunk : rawLines) {
            for (String line : chunk.lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (BoundedRegex.matcher(RUN_HEADER, trimmed).matches()) {
                    continue;
                }
                if (BoundedRegex.matcher(STATUS_HEADER, trimmed).matches()) {
                    continue;
                }
                diags.add(trimmed);
            }
        }
        if (diags.size() <= 15) {
            return diags;
        }
        List<String> bounded = new ArrayList<>(diags.subList(0, 15));
        int omitted = diags.size() - 15;
        bounded.add("... (" + omitted + " lines omitted) ...");
        return bounded;
    }

    private static List<String> extractPackageDiagnostics(List<String> rawLines) {
        if (rawLines == null || rawLines.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String chunk : rawLines) {
            for (String line : chunk.lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("FAIL\t") || trimmed.startsWith("FAIL ")) {
                    continue;
                }
                cleaned.add(trimmed);
                if (cleaned.size() >= 15) {
                    break;
                }
            }
            if (cleaned.size() >= 15) {
                break;
            }
        }
        return cleaned;
    }

    private static StageResult parsePlainGoTest(String raw, FilterContext context) {
        List<String> rawLines = raw.lines().toList();
        List<PlainFailedTest> failures = new ArrayList<>();
        Map<String, List<String>> testDiags = new LinkedHashMap<>();
        List<String> unassignedDiags = new ArrayList<>();
        String currentTest = null;

        for (String line : rawLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            Matcher runMatcher = BoundedRegex.matcher(RUN_OR_CONT, trimmed);
            if (runMatcher.matches()) {
                currentTest = runMatcher.group(1).trim();
                testDiags.computeIfAbsent(currentTest, k -> new ArrayList<>());
                unassignedDiags.clear();
                continue;
            }

            Matcher pauseMatcher = BoundedRegex.matcher(PAUSE_HEADER, trimmed);
            if (pauseMatcher.matches()) {
                String paused = pauseMatcher.group(1).trim();
                if (paused.equals(currentTest)) {
                    currentTest = null;
                }
                continue;
            }

            Matcher failMatcher = BoundedRegex.matcher(PLAIN_FAIL_TEST, trimmed);
            if (failMatcher.matches()) {
                String testName = failMatcher.group(1).trim();
                List<String> diags = testDiags.remove(testName);
                if (diags == null || diags.isEmpty()) {
                    if (!unassignedDiags.isEmpty()) {
                        diags = new ArrayList<>(unassignedDiags);
                        unassignedDiags.clear();
                    } else {
                        diags = List.of();
                    }
                }
                failures.add(new PlainFailedTest(testName, diags));
                if (testName.equals(currentTest)) {
                    currentTest = null;
                }
                continue;
            }

            Matcher colonMatcher = BoundedRegex.matcher(PLAIN_FAIL_COLON, trimmed);
            if (colonMatcher.matches()) {
                String testName = colonMatcher.group(1).trim();
                List<String> diags = testDiags.remove(testName);
                if (diags == null || diags.isEmpty()) {
                    if (!unassignedDiags.isEmpty()) {
                        diags = new ArrayList<>(unassignedDiags);
                        unassignedDiags.clear();
                    } else {
                        diags = List.of();
                    }
                }
                failures.add(new PlainFailedTest(testName, diags));
                if (testName.equals(currentTest)) {
                    currentTest = null;
                }
                continue;
            }

            Matcher passMatcher = BoundedRegex.matcher(PLAIN_PASS_OR_SKIP, trimmed);
            if (passMatcher.matches()) {
                String passTest = passMatcher.group(1).trim();
                testDiags.remove(passTest);
                if (passTest.equals(currentTest)) {
                    currentTest = null;
                }
                unassignedDiags.clear();
                continue;
            }

            if (BoundedRegex.matcher(STATUS_HEADER, trimmed).matches()) {
                continue;
            }

            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (currentTest != null) {
                    List<String> diags = testDiags.computeIfAbsent(currentTest, k -> new ArrayList<>());
                    if (diags.size() < 15) {
                        diags.add(trimmed);
                    } else if (diags.size() == 15) {
                        diags.add("... (diagnostics omitted) ...");
                    }
                } else {
                    if (unassignedDiags.size() < 15) {
                        unassignedDiags.add(trimmed);
                    }
                }
            }
        }

        if (failures.isEmpty()) {
            List<String> genericFailures = rawLines.stream()
                .filter(l -> l.startsWith("--- FAIL:") || l.startsWith("FAIL"))
                .limit(20)
                .toList();
            if (genericFailures.isEmpty()) {
                return StageResult.continueWith(raw);
            }
            StringBuilder sb = new StringBuilder("go test: ")
                .append(genericFailures.size()).append(" failure(s)\n");
            genericFailures.forEach(f -> sb.append("  ").append(f).append('\n'));
            return StageResult.continueWith(sb.toString().stripTrailing());
        }

        StringBuilder sb = new StringBuilder("go test: ")
            .append(failures.size()).append(" failure(s)\n");
        for (PlainFailedTest f : failures) {
            sb.append("  FAIL: ").append(f.name()).append('\n');
            for (String diag : f.diagnostics()) {
                sb.append("    ").append(diag).append('\n');
            }
        }

        publishPlainIr(context, failures);
        return StageResult.continueWith(sb.toString().stripTrailing());
    }

    private static void publishJsonIr(FilterContext context, List<FailedTest> failedTests, List<PackageFailure> packageFailures, int passed, int skipped) {
        if (context == null || context.documentBuilder() == null) {
            return;
        }
        List<Document.TestCase> cases = new ArrayList<>();
        for (FailedTest ft : failedTests) {
            cases.add(new Document.TestCase(
                ft.testName(),
                "FAILED",
                String.join("\n", ft.diagnostics()),
                ft.pkg(),
                null,
                null
            ));
        }
        for (PackageFailure pf : packageFailures) {
            cases.add(new Document.TestCase(
                pf.pkg() + " (" + pf.failureType() + ")",
                "FAILED",
                String.join("\n", pf.diagnostics()),
                pf.pkg(),
                null,
                null
            ));
        }
        int totalFailed = failedTests.size() + packageFailures.size();
        int total = passed + skipped + totalFailed;
        context.documentBuilder().test(new Document.TestDocument(
            cases,
            passed,
            totalFailed,
            skipped,
            List.of("go test: " + totalFailed + " failure(s)"),
            "",
            0,
            total,
            "go test"
        ));
    }

    private static void publishPlainIr(FilterContext context, List<PlainFailedTest> failures) {
        if (context == null || context.documentBuilder() == null) {
            return;
        }
        List<Document.TestCase> cases = new ArrayList<>();
        for (PlainFailedTest f : failures) {
            cases.add(new Document.TestCase(
                f.name(),
                "FAILED",
                String.join("\n", f.diagnostics()),
                null,
                null,
                null
            ));
        }
        context.documentBuilder().test(new Document.TestDocument(
            cases,
            0,
            failures.size(),
            0,
            List.of("go test: " + failures.size() + " failure(s)"),
            "",
            0,
            failures.size(),
            "go test"
        ));
    }

    private record FailedTest(String pkg, String testName, List<String> diagnostics) {}

    private record PackageFailure(String pkg, String failureType, List<String> diagnostics) {}

    private record PlainFailedTest(String name, List<String> diagnostics) {}
}
