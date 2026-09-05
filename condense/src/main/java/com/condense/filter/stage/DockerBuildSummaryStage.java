package com.condense.filter.stage;

import com.condense.filter.pipeline.CollectingSink;
import com.condense.filter.pipeline.EmissionSink;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.StageSession;
import com.condense.filter.pipeline.Streamability;
import com.condense.filter.strategy.BoundedRegex;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DockerBuildSummaryStage implements FilterStage {
    public static final DockerBuildSummaryStage INSTANCE = new DockerBuildSummaryStage();
    private static final Pattern IMAGE_ID =
        Pattern.compile("(?:Successfully built|writing image sha256:)\\s*([0-9a-f]{8,12})");
    private static final Pattern TAGGED =
        Pattern.compile("Successfully tagged (.+)");
    private static final Pattern STEP_DONE = Pattern.compile("^#\\d+ DONE\\b");
    private static final Pattern ERROR = Pattern.compile("(?i)\\bERROR\\b|failed to");
    private static final int MAX_DONE = 30;

    private DockerBuildSummaryStage() {}

    @Override
    public StageResult process(String input, FilterContext context) {
        CollectingSink sink = new CollectingSink();
        openSession().acceptDocument(input, sink, context);
        return StageResult.continueWith(sink.output());
    }

    @Override
    public Streamability streamability() {
        return Streamability.ORDER_LOCAL;
    }

    @Override
    public StageSession openSession() {
        return new Session();
    }

    private static final class Session implements StageSession {
        private String imageId;
        private String tag;
        private int doneCount;
        private boolean emittedAny;

        @Override
        public void feedLine(String line, EmissionSink sink, FilterContext context) {
            String value = line != null ? line : "";
            if (BoundedRegex.matcher(STEP_DONE, value).find() && doneCount < MAX_DONE) {
                sink.emit(value);
                doneCount++;
                emittedAny = true;
            } else if (BoundedRegex.matcher(ERROR, value).find()) {
                sink.emit(value);
                emittedAny = true;
            }
            Matcher id = BoundedRegex.matcher(IMAGE_ID, value);
            if (id.find()) {
                imageId = id.group(1);
            }
            Matcher tagged = BoundedRegex.matcher(TAGGED, value);
            if (tagged.find()) {
                tag = tagged.group(1).trim();
            }
        }

        @Override
        public void endOfInput(EmissionSink sink, FilterContext context) {
            int exit = context != null && context.result() != null ? context.result().exitCode() : 0;
            if (exit != 0) {
                if (emittedAny) {
                    sink.emit("docker build failed");
                }
                return;
            }
            StringBuilder sb = new StringBuilder("✓ docker build");
            if (imageId != null) {
                sb.append(": ").append(imageId);
            }
            if (tag != null) {
                sb.append(" → ").append(tag);
            }
            sink.emit(sb.toString());
        }
    }
}
