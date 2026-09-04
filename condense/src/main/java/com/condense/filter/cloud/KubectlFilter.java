package com.condense.filter.cloud;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.filter.strategy.TailLinesStage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("kubectl")
@ApplicationScoped
public class KubectlFilter extends PipelineBackedFilter {

    public KubectlFilter() {
        super();
    }

    @Inject
    public KubectlFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        return result.readStdout();
    }

    @Override
    protected FilterResult beforePipeline(String command, ExecutionResult result,
                                         CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            return FilterResult.passthrough(result);
        }
        if (result.readStdout().lines().toList().isEmpty()) {
            return FilterResult.passthrough(result);
        }
        if (command.contains("get") || command.contains("describe") || command.contains("logs")) {
            return null;
        }
        return FilterResult.passthrough(result);
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(KubectlDispatchStage.INSTANCE);
    }

    static final class KubectlDispatchStage implements com.condense.filter.pipeline.FilterStage {
        static final KubectlDispatchStage INSTANCE = new KubectlDispatchStage();
        private static final Pattern NOT_RUNNING =
            Pattern.compile("Error|CrashLoopBackOff|OOMKilled|Pending|Terminating", Pattern.CASE_INSENSITIVE);
        private static final TailLinesStage LOG_TAIL = new TailLinesStage(20, false, true);

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
}
