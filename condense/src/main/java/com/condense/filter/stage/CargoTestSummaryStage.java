package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@DeclarativeStage(aliases = {"cargo_test_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class CargoTestSummaryStage implements FilterStage {
    public static final CargoTestSummaryStage INSTANCE = new CargoTestSummaryStage();

    private static final Pattern CARGO_PASSED = Pattern.compile("(\\d+)\\s+passed");
    private static final Pattern CARGO_FAILED = Pattern.compile("(\\d+)\\s+failed");
    private static final Pattern CARGO_IGNORED = Pattern.compile("(\\d+)\\s+ignored");

    private CargoTestSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        List<String> failures = new ArrayList<>();
        String resultLine = null;
        boolean hasCompile = false;
        List<String> errors = new ArrayList<>();

        for (String line : raw.lines().toList()) {
            if (line.startsWith("test ") && line.contains("...") && line.endsWith("FAILED")) {
                failures.add("  FAILED: " + line.substring(5, line.indexOf(" ...")));
            } else if (line.startsWith("test result: ")
                && (line.contains("ok.") || line.contains("FAILED."))) {
                resultLine = line.trim();
            } else if (line.trim().startsWith("Compiling ")) {
                hasCompile = true;
            } else if (line.startsWith("error") || line.startsWith("  -->")) {
                if (errors.size() < 10) {
                    errors.add(line);
                }
            }
        }

        ExecutionResult result = context.result();
        CondenseConfig config = context.config();
        if (failures.isEmpty()) {
            if (result != null && result.exitCode() != 0 && hasCompile) {
                publishIr(context, failures, resultLine, true, errors);
                return StageResult.continueWith("cargo test: compile error\n" + String.join("\n", errors));
            }
            String summary = resultLine != null ? resultLine : "✓ all tests passed";
            if (config != null
                && !config.commandConfig("cargo-test").showTiming(true)
                && summary.contains("; finished in")) {
                summary = summary.substring(0, summary.indexOf("; finished in")).trim();
            }
            publishIr(context, failures, resultLine, false, errors);
            return StageResult.continueWith(summary);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("cargo test: ").append(failures.size()).append(" failure(s)\n");
        failures.forEach(f -> sb.append(f).append('\n'));
        if (resultLine != null) {
            String line = resultLine;
            if (config != null
                && !config.commandConfig("cargo-test").showTiming(true)
                && line.contains("; finished in")) {
                line = line.substring(0, line.indexOf("; finished in")).trim();
            }
            sb.append(line);
        }

        publishIr(context, failures, resultLine, false, errors);
        return StageResult.continueWith(sb.toString().stripTrailing());
    }

    private static void publishIr(FilterContext context, List<String> failures, String resultLine, boolean compileError, List<String> errors) {
        if (context == null || context.documentBuilder() == null) {
            return;
        }
        if (compileError) {
            context.documentBuilder().build(new Document.BuildDocument(
                "cargo test",
                "FAILED",
                Math.max(1, errors.size()),
                0,
                null,
                List.of(),
                errors
            ));
            return;
        }

        int passed = 0;
        int failed = failures.size();
        int ignored = 0;

        if (resultLine != null) {
            Matcher mp = CARGO_PASSED.matcher(resultLine);
            if (mp.find()) {
                passed = Integer.parseInt(mp.group(1));
            }
            Matcher mf = CARGO_FAILED.matcher(resultLine);
            if (mf.find()) {
                failed = Math.max(failed, Integer.parseInt(mf.group(1)));
            }
            Matcher mi = CARGO_IGNORED.matcher(resultLine);
            if (mi.find()) {
                ignored = Integer.parseInt(mi.group(1));
            }
        }
        int total = passed + failed + ignored;

        List<Document.TestCase> cases = new ArrayList<>();
        for (String f : failures) {
            String name = f.replaceFirst("^\\s*FAILED:\\s*", "").trim();
            cases.add(new Document.TestCase(
                name,
                "FAILED",
                f.trim(),
                null,
                null,
                null
            ));
        }

        List<String> summaryLines = resultLine != null ? List.of(resultLine) : List.of();
        context.documentBuilder().test(new Document.TestDocument(
            cases,
            passed,
            failed,
            ignored,
            summaryLines,
            "",
            0,
            total,
            "cargo test"
        ));
    }
}
