package com.condense.filter.git;

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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("git log")
@ApplicationScoped
public class GitLogFilter extends PipelineBackedFilter {

    public GitLogFilter() {
        super();
    }

    @Inject
    public GitLogFilter(FilterOverrideLoader overrideLoader) {
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
        return null;
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(GitLogStage.INSTANCE);
    }

    static final class GitLogStage implements com.condense.filter.pipeline.FilterStage {
        static final GitLogStage INSTANCE = new GitLogStage();
        private static final Pattern COMMIT_PATTERN =
            Pattern.compile("^commit ([0-9a-f]{8})[0-9a-f]*");

        @Override
        public StageResult process(String stdout, FilterContext context) {
            List<String> commits = new ArrayList<>();
            String currentHash = null;
            for (String line : stdout.lines().toList()) {
                var m = BoundedRegex.matcher(COMMIT_PATTERN, line);
                if (m.find()) {
                    currentHash = m.group(1);
                } else if (currentHash != null && !line.isBlank()
                    && !line.startsWith("Author:")
                    && !line.startsWith("Date:")
                    && !line.startsWith("Merge:")) {
                    commits.add(currentHash + " " + line.trim());
                    currentHash = null;
                }
            }
            if (commits.isEmpty()) {
                return StageResult.continueWith("(no commits)");
            }
            int limit = context.verbose() >= 2 ? commits.size() : Math.min(10, commits.size());
            String out = String.join("\n", commits.subList(0, limit));
            if (!context.ultraCompact() && commits.size() > limit) {
                out += "\n(+" + (commits.size() - limit) + " more)";
            }
            return StageResult.continueWith(out);
        }
    }
}
