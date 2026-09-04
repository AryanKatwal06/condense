package com.condense.filter.stage;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

import java.util.ArrayList;
import java.util.List;

public final class CargoTestSummaryStage implements FilterStage {
    public static final CargoTestSummaryStage INSTANCE = new CargoTestSummaryStage();

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
                return StageResult.continueWith("cargo test: compile error\n" + String.join("\n", errors));
            }
            String summary = resultLine != null ? resultLine : "✓ all tests passed";
            if (config != null
                && !config.commandConfig("cargo-test").showTiming(true)
                && summary.contains("; finished in")) {
                summary = summary.substring(0, summary.indexOf("; finished in")).trim();
            }
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
        return StageResult.continueWith(sb.toString().stripTrailing());
    }
}
