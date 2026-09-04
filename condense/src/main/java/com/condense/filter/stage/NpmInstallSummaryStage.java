package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;

import java.util.regex.Pattern;

public final class NpmInstallSummaryStage implements FilterStage {
    public static final NpmInstallSummaryStage INSTANCE = new NpmInstallSummaryStage();
    private static final Pattern ADDED_PATTERN = Pattern.compile("added (\\d+) packages?");
    private static final Pattern AUDIT_PATTERN = Pattern.compile("found (\\d+) vulnerabilit");

    private NpmInstallSummaryStage() {}

    @Override
    public StageResult process(String input, FilterContext context) {
        var added = BoundedRegex.matcher(ADDED_PATTERN, input);
        var audit = BoundedRegex.matcher(AUDIT_PATTERN, input);
        StringBuilder sb = new StringBuilder("✓ npm install");
        if (added.find()) {
            sb.append(": ").append(added.group(1)).append(" packages");
        }
        if (audit.find()) {
            sb.append(" | ").append(audit.group(0));
        }
        return StageResult.continueWith(sb.toString());
    }
}
