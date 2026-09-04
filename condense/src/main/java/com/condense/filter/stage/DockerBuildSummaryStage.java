package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DockerBuildSummaryStage implements FilterStage {
    public static final DockerBuildSummaryStage INSTANCE = new DockerBuildSummaryStage();
    private static final Pattern IMAGE_ID =
        Pattern.compile("(?:Successfully built|writing image sha256:)\\s*([0-9a-f]{8,12})");
    private static final Pattern TAGGED =
        Pattern.compile("Successfully tagged (.+)");

    private DockerBuildSummaryStage() {}

    @Override
    public StageResult process(String clean, FilterContext context) {
        StringBuilder sb = new StringBuilder("✓ docker build");
        Matcher id = BoundedRegex.matcher(IMAGE_ID, clean);
        if (id.find()) {
            sb.append(": ").append(id.group(1));
        }
        Matcher tag = BoundedRegex.matcher(TAGGED, clean);
        if (tag.find()) {
            sb.append(" → ").append(tag.group(1).trim());
        }
        return StageResult.continueWith(sb.toString());
    }
}
