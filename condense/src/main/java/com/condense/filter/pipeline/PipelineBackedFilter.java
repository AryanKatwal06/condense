package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.core.FilterStrategy;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.pipeline.config.PipelineDecision;
import com.condense.ir.Document;
import com.condense.ir.Documents;
import org.jboss.logging.Logger;

import java.nio.file.Path;
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

    /** Stderr first, then stdout — typical for npm/docker progress plus a result line. */
    protected static String stderrThenStdout(ExecutionResult result) {
        String err = result.readStderr();
        String out = result.readStdout();
        if (err.isBlank()) {
            return out;
        }
        if (out.isBlank()) {
            return err;
        }
        return err + "\n" + out;
    }

    protected final FilterOverrideLoader overrideLoader() {
        return overrideLoader;
    }

    protected final FilterPipeline defaultPipeline() {
        return defaultPipeline;
    }

    public final FilterPipeline resolveActivePipeline(String command) {
        return overrideLoader.resolvePipeline(command, defaultPipeline);
    }

    public final FilterResult evaluateGate(
            String command,
            ExecutionResult result,
            CondenseConfig config,
            int verbose,
            boolean ultraCompact) {
        return beforePipeline(command, result, config, verbose, ultraCompact);
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
                return attachOpaque(early, command, result);
            }
            String raw = selectInput(command, result, config, verbose, ultraCompact);
            FilterContext context = FilterContext.of(command, result, config, verbose, ultraCompact);
            FilterPipeline active = overrideLoader.resolvePipeline(command, defaultPipeline);
            String filtered = active.execute(raw, context);
            Document document = Documents.fromContext(
                context, command, filterName(), result, true, filtered);
            String filterName = filterName();
            List<FilterIncident> incidents = context.incidents().stream()
                .map(incident -> incident.withFilterName(filterName))
                .toList();
            return FilterResult.of(result, filtered, incidents).withDocument(document);
        } catch (Exception e) {
            log.warnf("%s error: %s — falling back to passthrough",
                filterName(), e.getMessage());
            return attachOpaque(
                FilterResult.fallbackPassthrough(result, filterName(), e.getMessage()),
                command,
                result);
        }
    }

    /**
     * Same control flow as {@link #apply} with a per-stage trace. Does not persist.
     */
    public final FilterExplainTrace explain(
            String command,
            ExecutionResult result,
            CondenseConfig config,
            int verbose,
            boolean ultraCompact,
            int droppedLimit,
            Path projectDir
    ) {
        String name = filterName();
        String definition = definitionName();
        String builtinSource = "classpath:filters/" + definition + ".toml";
        Path dir = projectDir != null ? projectDir : Path.of(System.getProperty("user.dir", "."));
        try {
            FilterResult early = beforePipeline(command, result, config, verbose, ultraCompact);
            if (early != null) {
                PipelineDecision decision = overrideLoader.resolveDecision(
                    command, defaultPipeline, dir, builtinSource);
                return new FilterExplainTrace(
                    attachOpaque(early, command, result), true, "passthrough", "beforePipeline", decision,
                    null, name, definition, selectInput(command, result, config, verbose, ultraCompact),
                    false
                );
            }
            String raw = selectInput(command, result, config, verbose, ultraCompact);
            FilterContext context = FilterContext.of(command, result, config, verbose, ultraCompact);
            PipelineDecision decision = overrideLoader.resolveDecision(
                command, defaultPipeline, dir, builtinSource);
            PipelineTrace trace = decision.pipeline().executeTraced(raw, context, droppedLimit);
            Document document = Documents.fromContext(
                context, command, name, result, true, trace.output());
            List<FilterIncident> incidents = context.incidents().stream()
                .map(incident -> incident.withFilterName(name))
                .toList();
            FilterResult filtered = FilterResult.of(result, trace.output(), incidents).withDocument(document);
            return new FilterExplainTrace(
                filtered, false, null, null, decision, trace, name, definition, raw, false);
        } catch (Exception e) {
            log.warnf("%s error: %s — falling back to passthrough", name, e.getMessage());
            FilterResult fallback = attachOpaque(
                FilterResult.fallbackPassthrough(result, name, e.getMessage()), command, result);
            PipelineDecision decision = overrideLoader.resolveDecision(
                command, defaultPipeline, dir, builtinSource);
            return new FilterExplainTrace(
                fallback, false, null, e.getMessage(), decision, null, name, definition, "", true);
        }
    }

    private FilterResult attachOpaque(FilterResult result, String command, ExecutionResult execution) {
        return result.withDocument(Documents.fromResult(command, filterName(), execution, result));
    }
}
