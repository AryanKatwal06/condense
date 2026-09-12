package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;
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
                String compileOutput = "cargo test: compile error\n" + String.join("\n", errors);
                publishIr(context, failures, resultLine, true, errors, compileOutput.lines().toList());
                return StageResult.continueWith(compileOutput);
            }
            String summary = resultLine != null ? resultLine : "✓ all tests passed";
            if (config != null
                && !config.commandConfig("cargo-test").showTiming(true)
                && summary.contains("; finished in")) {
                summary = summary.substring(0, summary.indexOf("; finished in")).trim();
            }
            publishIr(context, failures, resultLine, false, errors, List.of(summary));
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

        String output = sb.toString().stripTrailing();
        publishIr(context, failures, resultLine, false, errors, output.lines().toList());
        return StageResult.continueWith(output);
    }

    private static void publishIr(FilterContext context, List<String> failures, String resultLine, boolean compileError, List<String> errors, List<String> outputLines) {
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
            Matcher mp = BoundedRegex.matcher(CARGO_PASSED, resultLine);
            if (mp.find()) {
                passed = Integer.parseInt(mp.group(1));
            }
            Matcher mf = BoundedRegex.matcher(CARGO_FAILED, resultLine);
            if (mf.find()) {
                failed = Math.max(failed, Integer.parseInt(mf.group(1)));
            }
            Matcher mi = BoundedRegex.matcher(CARGO_IGNORED, resultLine);
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

        context.documentBuilder().test(new Document.TestDocument(
            cases,
            passed,
            failed,
            ignored,
            outputLines,
            "",
            0,
            total,
            "cargo test"
        ));
    }
}
