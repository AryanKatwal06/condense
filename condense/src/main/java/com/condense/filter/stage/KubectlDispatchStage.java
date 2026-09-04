package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.filter.strategy.TailLinesStage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class KubectlDispatchStage implements FilterStage {
    public static final KubectlDispatchStage INSTANCE = new KubectlDispatchStage();
    private static final Pattern NOT_RUNNING =
        Pattern.compile("Error|CrashLoopBackOff|OOMKilled|Pending|Terminating", Pattern.CASE_INSENSITIVE);
    private static final TailLinesStage LOG_TAIL = new TailLinesStage(20, false, true);

    private KubectlDispatchStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        String command = context.command() != null ? context.command() : "";
        List<String> lines = raw.lines().toList();
        if (command.contains("get") || command.contains("describe")) {
            return compactTable(lines);
        }
        if (command.contains("logs")) {
            return LOG_TAIL.process(raw, context);
        }
        return StageResult.continueWith(raw);
    }

    private static StageResult compactTable(List<String> lines) {
        List<String> unhealthy = new ArrayList<>();
        List<String> healthy = new ArrayList<>();
        if (!lines.isEmpty()) {
            healthy.add(lines.get(0));
        }
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (BoundedRegex.find(NOT_RUNNING, line)) {
                unhealthy.add("⚠ " + line.trim());
            } else {
                healthy.add(line);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!unhealthy.isEmpty()) {
            sb.append("UNHEALTHY PODS:\n");
            unhealthy.forEach(l -> sb.append("  ").append(l).append('\n'));
            sb.append('\n');
        }
        healthy.forEach(l -> sb.append(l).append('\n'));
        return StageResult.continueWith(sb.toString().stripTrailing());
    }
}
