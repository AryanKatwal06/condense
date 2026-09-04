package com.condense.filter.stage;

import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CargoInstallSummaryStage implements FilterStage {
    public static final CargoInstallSummaryStage INSTANCE = new CargoInstallSummaryStage();
    private static final Pattern FINISHED = Pattern.compile("Finished .+ in (.+)");
    private static final Pattern ERROR_LINE = Pattern.compile("^error(\\[.+?\\])?:", Pattern.MULTILINE);

    private CargoInstallSummaryStage() {}

    @Override
    public StageResult process(String clean, FilterContext context) {
        ExecutionResult result = context.result();
        if (result != null && !result.succeeded()) {
            List<String> errors = clean.lines()
                .filter(l -> BoundedRegex.find(ERROR_LINE, l))
                .limit(15)
                .toList();
            return StageResult.continueWith(errors.isEmpty() ? clean : String.join("\n", errors));
        }
        Matcher fin = BoundedRegex.matcher(FINISHED, clean);
        if (fin.find()) {
            return StageResult.continueWith("✓ " + fin.group(0).trim());
        }
        String verb = "complete";
        if (context.command() != null) {
            String[] parts = context.command().split(" ");
            if (parts.length > 1) {
                verb = parts[1] + " complete";
            }
        }
        return StageResult.continueWith("✓ " + verb);
    }
}
