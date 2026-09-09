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
import java.util.regex.Pattern;

@DeclarativeStage(aliases = {"mvn_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class MvnSummaryStage implements FilterStage {
    public static final MvnSummaryStage INSTANCE = new MvnSummaryStage();
    private static final Pattern BUILD_SUCCESS = Pattern.compile("BUILD SUCCESS");
    private static final Pattern BUILD_FAILURE = Pattern.compile("BUILD FAILURE");
    private static final Pattern ERROR_LINE = Pattern.compile("^\\[ERROR\\]");
    private static final Pattern TEST_FAIL = Pattern.compile("Tests run:.+Failures: [1-9]");

    private MvnSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        boolean isSuccess = false;
        boolean isFailure = false;
        String testLine = "";
        List<String> errors = new ArrayList<>();

        for (String line : raw.lines().toList()) {
            if (BoundedRegex.find(BUILD_SUCCESS, line)) {
                isSuccess = true;
            }
            if (BoundedRegex.find(BUILD_FAILURE, line)) {
                isFailure = true;
            }
            if (line.contains("Tests run:")) {
                testLine = line;
            }
            if (BoundedRegex.find(ERROR_LINE, line) || BoundedRegex.find(TEST_FAIL, line)) {
                errors.add(line.trim());
            }
        }

        String output;
        String status = "UNKNOWN";
        if (isSuccess) {
            status = "SUCCESS";
            String tl = testLine.trim();
            CondenseConfig config = context.config();
            if (!tl.isBlank() && config != null
                && !config.commandConfig("mvn").showTiming(true)
                && tl.contains(", Time elapsed:")) {
                tl = tl.substring(0, tl.indexOf(", Time elapsed:")).trim();
            }
            output = "✓ BUILD SUCCESS" + (tl.isBlank() ? "" : " — " + tl);
        } else if (isFailure) {
            status = "FAILURE";
            errors = errors.subList(0, Math.min(20, errors.size()));
            output = "✗ BUILD FAILURE\n" + String.join("\n", errors);
        } else {
            ExecutionResult result = context.result();
            output = result != null ? result.combined() : raw;
        }

        if (context != null && context.documentBuilder() != null) {
            List<String> summaryLines = output != null ? output.lines().toList() : List.of();
            context.documentBuilder().build(new Document.BuildDocument(
                "mvn",
                status,
                errors.size(),
                0,
                null,
                List.of(),
                summaryLines
            ));
        }

        return StageResult.continueWith(output);
    }
}
