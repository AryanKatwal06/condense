package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.core.FilterStrategy;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;

/**
 * Adapter base for domain filters that execute through {@link FilterPipeline}.
 *
 * <p>{@link #apply} is final so a filter cannot grow a second parser. Subclasses
 * supply gates ({@link #beforePipeline}), the input stream ({@link #selectInput}),
 * and {@link #definitionName()} for the classpath builtin pipeline.
 *
 * <p>Production CDI uses the injected {@link FilterOverrideLoader} singleton.
 * Corpus tests and {@code new XxxFilter()} use {@link FilterOverrideLoader#standalone()}
 * so thirty-two no-arg constructors do not each allocate a private uncached loader.
 */
public abstract class PipelineBackedFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(PipelineBackedFilter.class);

    private final FilterOverrideLoader overrideLoader;
    private final FilterPipeline defaultPipeline;

    protected PipelineBackedFilter() {
        this(FilterOverrideLoader.standalone());
    }

    protected PipelineBackedFilter(FilterOverrideLoader overrideLoader) {
        this.overrideLoader = overrideLoader != null ? overrideLoader : FilterOverrideLoader.standalone();
        this.defaultPipeline = Objects.requireNonNull(buildPipeline(), "buildPipeline must not return null");
    }

    /**
     * Builtin definition name under {@code classpath:filters/<name>.toml}.
     * Called once from the constructor.
     */
    protected abstract String definitionName();

    /**
     * Loads the compiled default pipeline from {@link BuiltinDefinitionCatalog}.
     */
    protected final FilterPipeline buildPipeline() {
        return BuiltinDefinitionCatalog.standalone().requiredPipeline(definitionName());
    }

    /**
     * Optional pre-pipeline gate. Return a result to skip the pipeline
     * (failure passthrough, verbose/size passthrough, grep exit 1, and similar).
     * Return {@code null} to continue.
     *
     * <p>Gates that decide whether filtering runs at all stay here. They must
     * not become fail-open middle stages that can be skipped after an exception.
     */
    protected FilterResult beforePipeline(
            String command,
            ExecutionResult result,
            CondenseConfig config,
            int verbose,
            boolean ultraCompact) {
        return null;
    }

    /**
     * Text fed to the pipeline. Default is stdout, or stderr when stdout is blank.
     */
    protected String selectInput(
            String command,
            ExecutionResult result,
            CondenseConfig config,
            int verbose,
            boolean ultraCompact) {
        String stdout = result.readStdout();
        return stdout.isBlank() ? result.readStderr() : stdout;
    }

    protected final FilterOverrideLoader overrideLoader() {
        return overrideLoader;
    }

    protected final FilterPipeline defaultPipeline() {
        return defaultPipeline;
    }

    private String filterName() {
        String simple = getClass().getSimpleName();
        return simple == null || simple.isBlank() ? getClass().getName() : simple;
    }

    @Override
    public final FilterResult apply(
            String command,
            ExecutionResult result,
            CondenseConfig config,
            int verbose,
            boolean ultraCompact) {
        try {
            FilterResult early = beforePipeline(command, result, config, verbose, ultraCompact);
            if (early != null) {
                return early;
            }
            String raw = selectInput(command, result, config, verbose, ultraCompact);
            FilterContext context = FilterContext.of(command, result, config, verbose, ultraCompact);
            FilterPipeline active = overrideLoader.resolvePipeline(command, defaultPipeline);
            String filtered = active.execute(raw, context);
            String filterName = filterName();
            List<FilterIncident> incidents = context.incidents().stream()
                .map(incident -> incident.withFilterName(filterName))
                .toList();
            return FilterResult.of(result, filtered, incidents);
        } catch (Exception e) {
            log.warnf("%s error: %s — falling back to passthrough",
                filterName(), e.getMessage());
            return FilterResult.fallbackPassthrough(result, filterName(), e.getMessage());
        }
    }
}
