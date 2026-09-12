package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.Mappers;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
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
 * text {@code go test} output.
 */
@DeclarativeStage(aliases = {"go_test_summary", "go-test-summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class GoTestSummaryStage implements FilterStage {

    public static final GoTestSummaryStage INSTANCE = new GoTestSummaryStage();
    private static final ObjectMapper MAPPER = Mappers.JSON;

    private static final Pattern RUN_HEADER =
        Pattern.compile("^=== (?:RUN|PAUSE|CONT)\\s+.*$");
    private static final Pattern STATUS_HEADER =
        Pattern.compile("^--- (?:PASS|FAIL|SKIP):\\s+.*$");
    private static final Pattern PLAIN_FAIL_TEST =
        Pattern.compile("^--- FAIL:\\s+([^\\s(]+).*$");
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

        if (passed == 0 && skipped == 0 && failedTests.isEmpty()) {
            if (!failedPackages.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String pkg : failedPackages) {
                    List<String> rawPkgOutput = pkgBuffers.get(pkg);
                    List<String> cleanPkgDiags = extractPackageDiagnostics(rawPkgOutput);
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append("go test: build failed in ").append(pkg).append('\n');
                    for (String d : cleanPkgDiags) {
                        sb.append("  ").append(d).append('\n');
                    }
                }
                return StageResult.continueWith(sb.toString().stripTrailing());
            }
            return parsePlainGoTest(raw, context);
        }

        StringBuilder sb = new StringBuilder();
        if (!failedTests.isEmpty()) {
            sb.append("go test: ").append(failedTests.size()).append(" failure(s)\n");
            for (FailedTest failure : failedTests) {
                sb.append("  FAIL: ").append(failure.testName).append('\n');
                for (String diag : failure.diagnostics) {
                    sb.append("    ").append(diag).append('\n');
                }
            }
        }
        sb.append("passed: ").append(passed);
        if (skipped > 0) {
            sb.append(" | skipped: ").append(skipped);
        }
        if (!failedTests.isEmpty()) {
            sb.append(" | failed: ").append(failedTests.size());
        }
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
        List<String> pendingDiags = new ArrayList<>();

        for (String line : rawLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            Matcher failMatcher = BoundedRegex.matcher(PLAIN_FAIL_TEST, trimmed);
            if (failMatcher.matches()) {
                String testName = failMatcher.group(1).trim();
                failures.add(new PlainFailedTest(testName, new ArrayList<>(pendingDiags)));
                pendingDiags.clear();
                continue;
            }

            Matcher colonMatcher = BoundedRegex.matcher(PLAIN_FAIL_COLON, trimmed);
            if (colonMatcher.matches()) {
                String testName = colonMatcher.group(1).trim();
                failures.add(new PlainFailedTest(testName, new ArrayList<>(pendingDiags)));
                pendingDiags.clear();
                continue;
            }

            if (BoundedRegex.matcher(RUN_HEADER, trimmed).matches()) {
                pendingDiags.clear();
                continue;
            }

            if (BoundedRegex.matcher(STATUS_HEADER, trimmed).matches()) {
                continue;
            }

            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (pendingDiags.size() < 15) {
                    pendingDiags.add(trimmed);
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
        return StageResult.continueWith(sb.toString().stripTrailing());
    }

    private record FailedTest(String pkg, String testName, List<String> diagnostics) {}

    private record PlainFailedTest(String name, List<String> diagnostics) {}
}
