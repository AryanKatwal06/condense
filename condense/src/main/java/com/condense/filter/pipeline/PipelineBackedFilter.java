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
    private final String catalogDefinitionName;

    protected PipelineBackedFilter() {
        this(FilterOverrideLoader.standalone());
    }

    protected PipelineBackedFilter(FilterOverrideLoader overrideLoader) {
        this(overrideLoader, (String) null);
    }

    /**
     * Catalog-hosted filters pass {@code catalogDefinitionName} so
     * {@link #definitionName()} is available during {@link #buildPipeline()}.
     */
    protected PipelineBackedFilter(FilterOverrideLoader overrideLoader, String catalogDefinitionName) {
        this.overrideLoader = overrideLoader != null ? overrideLoader : FilterOverrideLoader.standalone();
        this.catalogDefinitionName = catalogDefinitionName;
        this.defaultPipeline = Objects.requireNonNull(buildPipeline(), "buildPipeline must not return null");
    }

    PipelineBackedFilter(FilterOverrideLoader overrideLoader, FilterPipeline defaultPipeline) {
        this.overrideLoader = overrideLoader != null ? overrideLoader : FilterOverrideLoader.standalone();
        this.catalogDefinitionName = null;
        this.defaultPipeline = Objects.requireNonNull(defaultPipeline, "defaultPipeline must not return null");
    }

    /**
     * Builtin definition name under {@code classpath:filters/<name>.toml}.
     * Called once from the constructor.
     */
    protected String definitionName() {
        if (catalogDefinitionName != null && !catalogDefinitionName.isBlank()) {
            return catalogDefinitionName;
        }
        throw new IllegalStateException(getClass().getName() + " must override definitionName()");
    }

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
        String stderr = result.readStderr();
        if (result.exitCode() != 0 && !stderr.isBlank() && !stdout.isBlank()) {
            return stdoutThenStderr(result);
        }
        return stdout.isBlank() ? stderr : stdout;
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

    /** Stdout first, then stderr — keep machine output ahead of diagnostic chatter. */
    protected static String stdoutThenStderr(ExecutionResult result) {
        String out = result.readStdout();
        String err = result.readStderr();
        if (out.isBlank()) {
            return err;
        }
        if (err.isBlank()) {
            return out;
        }
        return out + "\n" + err;
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

    protected String filterName() {
        if (catalogDefinitionName != null && !catalogDefinitionName.isBlank()) {
            return catalogDefinitionName;
        }
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
            FidelityGuard.Verdict fidelity = FidelityGuard.INSTANCE.check(
                command, result.exitCode(), raw, filtered);
            if (!fidelity.passed()) {
                log.warnf("%s fidelity check failed: %s — falling back to passthrough",
                    filterName(), fidelity.detail());
                FilterResult fallback = FilterResult.fallbackPassthrough(
                    result, filterName(), fidelity.detail());
                Document opaque = Document.opaque(
                    command, filterName(), result.exitCode(), false,
                    Documents.provenance(false), raw)
                    .withTermination(result.termination());
                return attachOpaque(fallback.withDocument(opaque), command, result);
            }
            // Filters that already render a compact semantic summary are not
            // pointless header noise. For everything else, a successful output
            // that inflates both characters and tokens falls back to raw.
            if (result.exitCode() == 0
                && !isIntentionalCompactOutput(command, raw, filtered)
                && NetWinGate.isNetLoss(raw, filtered, ultraCompact)) {
                log.warnf("%s output inflates token count — falling back to passthrough",
                    filterName());
                FilterResult passthrough = FilterResult.passthrough(result);
                Document opaque = Document.opaque(
                    command, filterName(), result.exitCode(), false,
                    Documents.provenance(false), raw)
                    .withTermination(result.termination());
                return attachOpaque(passthrough.withDocument(opaque), command, result);
            }
            String output = withIdentifierFactsheet(raw, filtered);
            Document document = Documents.fromContext(
                context, command, filterName(), result, true, output);
            String filterName = filterName();
            List<FilterIncident> incidents = context.incidents().stream()
                .map(incident -> incident.withFilterName(filterName))
                .toList();
            return FilterResult.of(result, output, incidents).withDocument(document);
        } catch (Exception | StackOverflowError e) {
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
            FidelityGuard.Verdict fidelity = FidelityGuard.INSTANCE.check(
                command, result.exitCode(), raw, trace.output());
            if (!fidelity.passed()) {
                // explain must show the same bytes apply() would print. apply()
                // rejects a lossy compression at this point, so report the raw
                // passthrough and surface the guard as a fired gate.
                FilterResult fallback = attachOpaque(
                    FilterResult.fallbackPassthrough(result, name, fidelity.detail()), command, result);
                return new FilterExplainTrace(
                    fallback, true, "fidelity_guard", fidelity.detail(), decision, trace,
                    name, definition, raw, true);
            }
            if (result.exitCode() == 0
                && !isIntentionalCompactOutput(command, raw, trace.output())
                && NetWinGate.isNetLoss(raw, trace.output(), ultraCompact)) {
                FilterResult fallback = attachOpaque(
                    FilterResult.passthrough(result), command, result);
                return new FilterExplainTrace(
                    fallback, true, "net_win_gate", "filtered output inflates token count",
                    decision, trace, name, definition, raw, true);
            }
            String output = withIdentifierFactsheet(raw, trace.output());
            Document document = Documents.fromContext(
                context, command, name, result, true, output);
            List<FilterIncident> incidents = context.incidents().stream()
                .map(incident -> incident.withFilterName(name))
                .toList();
            FilterResult filtered = FilterResult.of(result, output, incidents).withDocument(document);
            return new FilterExplainTrace(
                filtered, false, null, null, decision, trace, name, definition, raw, false);
        } catch (Exception | StackOverflowError e) {
            log.warnf("%s error: %s — falling back to passthrough", name, e.getMessage());
            FilterResult fallback = attachOpaque(
                FilterResult.fallbackPassthrough(result, name, e.getMessage()), command, result);
            PipelineDecision decision = overrideLoader.resolveDecision(
                command, defaultPipeline, dir, builtinSource);
            return new FilterExplainTrace(
                fallback, false, null, e.getMessage(), decision, null, name, definition, "", true);
        }
    }

    /**
     * Some filters intentionally replace a tiny capture with a compact semantic
     * summary even when the summary is a few characters longer. Others prepend
     * a clause summary but keep every raw line. Neither is pointless header
     * noise, so the net-win gate must not replace them.
     */
    private static boolean isIntentionalCompactOutput(String command, String raw, String filtered) {
        if (command == null || raw == null || filtered == null) {
            return false;
        }
        String normalized = command.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("\\s+", " ")
            .strip();
        if (normalized.equals("npm install") || normalized.equals("npm ci")) {
            return true;
        }
        boolean preservingSummary = normalized.equals("terraform state list")
            || normalized.equals("tofu state list")
            || normalized.startsWith("kubectl get pods");
        if (!preservingSummary) {
            return false;
        }
        for (String line : raw.lines().toList()) {
            if (!line.isBlank() && !filtered.contains(line)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Append a bounded identifier factsheet only for large, heavily compressed
     * outputs. The size floor and reduction threshold keep locked corpus goldens
     * and typical command output byte-identical.
     */
    private static String withIdentifierFactsheet(String raw, String filtered) {
        if (raw == null || filtered == null) {
            return filtered;
        }
        if (raw.length() < IdentifierFactsheet.APPEND_MIN_RAW_CHARS) {
            return filtered;
        }
        if ((long) filtered.length() * 100
            > (long) raw.length() * IdentifierFactsheet.APPEND_MAX_FILTERED_PERCENT) {
            return filtered;
        }
        List<String> dropped = IdentifierFactsheet.extractDroppedIdentifiers(raw, filtered);
        String line = IdentifierFactsheet.formatFactsheetLine(dropped);
        return line.isEmpty() ? filtered : filtered + "\n" + line;
    }

    private FilterResult attachOpaque(FilterResult result, String command, ExecutionResult execution) {
        return result.withDocument(Documents.fromResult(command, filterName(), execution, result));
    }
}
