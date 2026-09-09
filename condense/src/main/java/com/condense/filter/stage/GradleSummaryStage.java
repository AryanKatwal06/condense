package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.ir.Document;

import java.util.List;
import java.util.regex.Pattern;

@DeclarativeStage(aliases = {"gradle_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class GradleSummaryStage implements FilterStage {
    public static final GradleSummaryStage INSTANCE = new GradleSummaryStage();
    private static final Pattern BUILD_SUCCESSFUL = Pattern.compile("BUILD SUCCESSFUL");
    private static final Pattern BUILD_FAILED = Pattern.compile("BUILD FAILED");
    private static final Pattern FAILURE_DETAIL = Pattern.compile("^> ", Pattern.MULTILINE);

    private GradleSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        String output;
        boolean isSuccess = BoundedRegex.find(BUILD_SUCCESSFUL, raw);
        boolean isFailure = BoundedRegex.find(BUILD_FAILED, raw);
        List<String> details = List.of();
        if (isSuccess) {
            String duration = raw.lines()
                .filter(l -> l.contains("BUILD SUCCESSFUL"))
                .findFirst().map(String::trim).orElse("BUILD SUCCESSFUL");
            output = "✓ " + duration;
        } else if (isFailure) {
            details = raw.lines()
                .filter(l -> BoundedRegex.find(FAILURE_DETAIL, l) || l.startsWith("FAILURE:"))
                .limit(15)
                .toList();
            output = "✗ BUILD FAILED\n" + String.join("\n", details);
        } else {
            output = context.result() != null ? context.result().combined() : raw;
        }

        if (context != null && context.documentBuilder() != null) {
            String status = isSuccess ? "SUCCESS" : (isFailure ? "FAILED" : "UNKNOWN");
            List<String> summaryLines = output != null ? output.lines().toList() : List.of();
            context.documentBuilder().build(new Document.BuildDocument(
                "gradle",
                status,
                isFailure ? Math.max(1, details.size()) : 0,
                0,
                null,
                List.of(),
                summaryLines
            ));
        }

        return StageResult.continueWith(output);
    }
}
