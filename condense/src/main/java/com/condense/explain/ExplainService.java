package com.condense.explain;

import com.condense.analytics.EstimatorInfo;
import com.condense.core.CommandExecutor;
import com.condense.core.CondenseConfig;
import com.condense.core.ConfigLoader;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.core.FilterStrategy;
import com.condense.core.StrategyRegistry;
import com.condense.core.TokenCounter;
import com.condense.filter.pipeline.FilterExplainTrace;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.LineDiff;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.PipelineMode;
import com.condense.filter.pipeline.StageTrace;
import com.condense.filter.pipeline.Streamability;
import com.condense.filter.pipeline.config.PipelineDecision;
import com.condense.filter.python.PythonFilter;
import com.condense.trust.Provenance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an {@link ExplainReport} without writing analytics or tee files.
 */
@ApplicationScoped
public class ExplainService {

    public static final int MAX_INPUT_BYTES = 10 * 1024 * 1024;
    public static final int DEFAULT_DROPPED_LIMIT = 32;

    private final StrategyRegistry registry;
    private final CommandExecutor executor;
    private final ConfigLoader configLoader;

    public ExplainService() {
        this(null, null, null);
    }

    @Inject
    public ExplainService(StrategyRegistry registry, CommandExecutor executor, ConfigLoader configLoader) {
        this.registry = registry;
        this.executor = executor;
        this.configLoader = configLoader;
    }

    public ExplainReport explainExecuted(
            List<String> args,
            int verbose,
            boolean ultraCompact,
            int droppedLimit
    ) throws Exception {
        if (executor == null) {
            throw new IllegalStateException("Command execution is not available");
        }
        ExecutionResult result = executor.execute(args, CommandExecutor.resolveProxyTimeout());
        return explainArgs(args, result, verbose, ultraCompact, droppedLimit, cwd());
    }

    public ExplainReport explainInput(
            List<String> args,
            Path inputFile,
            int exitCode,
            int verbose,
            boolean ultraCompact,
            int droppedLimit,
            Path projectDir
    ) throws IOException {
        byte[] bytes = readBounded(inputFile);
        ExecutionResult result = new ExecutionResult(
            exitCode, new String(bytes, StandardCharsets.UTF_8), "", 0L);
        return explainArgs(args, result, verbose, ultraCompact, droppedLimit, projectDir);
    }

    public ExplainReport explainStdin(
            List<String> args,
            byte[] stdinBytes,
            int exitCode,
            int verbose,
            boolean ultraCompact,
            int droppedLimit,
            Path projectDir
    ) {
        if (stdinBytes != null && stdinBytes.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException(
                "Input exceeds the " + MAX_INPUT_BYTES + " byte capture cap");
        }
        String text = stdinBytes == null ? "" : new String(stdinBytes, StandardCharsets.UTF_8);
        ExecutionResult result = new ExecutionResult(exitCode, text, "", 0L);
        return explainArgs(args, result, verbose, ultraCompact, droppedLimit, projectDir);
    }

    public ExplainReport explainArgs(
            List<String> args,
            ExecutionResult result,
            int verbose,
            boolean ultraCompact,
            int droppedLimit,
            Path projectDir
    ) {
        String command = String.join(" ", args);
        FilterStrategy strategy = lookup(args);
        CondenseConfig config = configLoader != null ? configLoader.load() : CondenseConfig.defaults();
        return explainStrategy(strategy, command, result, config, verbose, ultraCompact, droppedLimit, projectDir);
    }

    public ExplainReport explainStrategy(
            FilterStrategy strategy,
            String command,
            ExecutionResult result,
            CondenseConfig config,
            int verbose,
            boolean ultraCompact,
            int droppedLimit,
            Path projectDir
    ) {
        FilterStrategy resolved = strategy;
        if (resolved instanceof PythonFilter python) {
            FilterStrategy routed = python.routedStrategy(command);
            if (routed == null) {
                FilterResult passthrough = resolved.apply(command, result, config, verbose, ultraCompact);
                return passthroughReport(command, "PythonFilter", result, passthrough);
            }
            resolved = routed;
        }
        if (resolved instanceof PipelineBackedFilter pipeline) {
            return fromTrace(command, result, pipeline.explain(
                command, result, config, verbose, ultraCompact, droppedLimit, projectDir));
        }
        FilterResult applied = resolved == null
            ? FilterResult.passthrough(result)
            : resolved.apply(command, result, config, verbose, ultraCompact);
        String filterName = resolved == null ? "passthrough" : simpleName(resolved.getClass());
        return passthroughReport(command, filterName, result, applied);
    }

    private FilterStrategy lookup(List<String> args) {
        if (registry == null) {
            return null;
        }
        return registry.lookup(args.toArray(String[]::new));
    }

    private static ExplainReport fromTrace(String command, ExecutionResult result, FilterExplainTrace trace) {
        FilterResult filtered = trace.result();
        List<ExplainReport.Stage> stages = new ArrayList<>();
        String pipelineInput = trace.selectedInput() == null ? "" : trace.selectedInput();
        String lastOutput = pipelineInput;
        if (trace.pipelineTrace() != null) {
            for (StageTrace stage : trace.pipelineTrace().stages()) {
                stages.add(toStage(stage, streamabilityOf(trace, stage.id())));
            }
            lastOutput = trace.pipelineTrace().output();
        }
        boolean stamped = filtered.wasFiltered();
        if (stamped) {
            StageTrace provenance = StageTrace.of(
                "provenance",
                StageTrace.RAN,
                lastOutput,
                filtered.output(),
                false,
                null,
                Integer.MAX_VALUE
            );
            stages.add(toStage(provenance, "document"));
            lastOutput = filtered.output();
        } else {
            lastOutput = filtered.output();
        }

        String firstInput = pipelineInput;
        if (trace.gateFired()) {
            firstInput = pipelineInput;
            lastOutput = filtered.output();
        }
        int inputLines = LineDiff.split(firstInput).length;
        int outputLines = LineDiff.split(lastOutput).length;
        int inputTokens = TokenCounter.count(firstInput);
        int outputTokens = TokenCounter.count(lastOutput);

        PipelineDecision decision = trace.decision();
        List<ExplainReport.SkippedTier> skipped = new ArrayList<>();
        if (decision != null && decision.skipped() != null) {
            for (PipelineDecision.SkippedTier item : decision.skipped()) {
                skipped.add(new ExplainReport.SkippedTier(item.tier(), item.reason(), item.source()));
            }
        }

        List<ExplainReport.Incident> incidents = new ArrayList<>();
        if (filtered.incidents() != null) {
            for (var incident : filtered.incidents()) {
                incidents.add(new ExplainReport.Incident(
                    incident.kind(), incident.stageName(), incident.detail()));
            }
        }

        return new ExplainReport(
            command,
            trace.filterName(),
            decision == null ? PipelineDecision.TIER_BUILTIN : decision.tier(),
            decision == null ? null : decision.source(),
            skipped,
            new ExplainReport.Gate(trace.gateFired(), trace.gateKind(), trace.gateDetail()),
            inputLines,
            outputLines,
            inputTokens,
            outputTokens,
            inputLines - outputLines,
            inputTokens - outputTokens,
            filtered.rawTokens(),
            filtered.outTokens(),
            filtered.wasFiltered(),
            stages,
            new ExplainReport.ProvenanceInfo(stamped, stamped ? Provenance.STAMP : null),
            filtered.output(),
            EstimatorInfo.current(),
            incidents,
            result.exitCode(),
            !trace.applyFallback(),
            pipelineMode(trace)
        );
    }

    private static ExplainReport passthroughReport(
            String command,
            String filterName,
            ExecutionResult result,
            FilterResult filtered
    ) {
        String output = filtered.output() == null ? "" : filtered.output();
        int inputLines = LineDiff.split(output).length;
        int outputLines = inputLines;
        int tokens = TokenCounter.count(output);
        List<ExplainReport.SkippedTier> skipped = new ArrayList<>();
        skipped.add(new ExplainReport.SkippedTier(PipelineDecision.TIER_PROJECT, "absent", null));
        skipped.add(new ExplainReport.SkippedTier(PipelineDecision.TIER_GLOBAL, "absent", null));
        return new ExplainReport(
            command,
            filterName,
            PipelineDecision.TIER_PASSTHROUGH,
            null,
            skipped,
            new ExplainReport.Gate(false, null, null),
            inputLines,
            outputLines,
            tokens,
            tokens,
            0,
            0,
            filtered.rawTokens(),
            filtered.outTokens(),
            filtered.wasFiltered(),
            new ArrayList<>(),
            new ExplainReport.ProvenanceInfo(false, null),
            output,
            EstimatorInfo.current(),
            new ArrayList<>(),
            result.exitCode(),
            true,
            "live_raw"
        );
    }

    private static String pipelineMode(FilterExplainTrace trace) {
        if (trace.gateFired() || trace.decision() == null || trace.decision().pipeline() == null) {
            return "capture";
        }
        return trace.decision().pipeline().mode() == PipelineMode.STREAM ? "stream" : "capture";
    }

    private static String streamabilityOf(FilterExplainTrace trace, String id) {
        if (trace.decision() == null || trace.decision().pipeline() == null) {
            return "document";
        }
        for (FilterStage stage : trace.decision().pipeline().stages()) {
            if (id.equals(stage.stageId())) {
                Streamability streamability = stage.streamability();
                return streamability.name().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "document";
    }

    private static ExplainReport.Stage toStage(StageTrace stage, String streamability) {
        return new ExplainReport.Stage(
            stage.id(),
            stage.status(),
            stage.inputLines(),
            stage.outputLines(),
            stage.inputTokens(),
            stage.outputTokens(),
            stage.droppedLines(),
            stage.addedLines(),
            stage.keptLines(),
            stage.shortCircuit(),
            stage.droppedSample(),
            stage.addedSample(),
            stage.droppedTruncated(),
            stage.addedTruncated(),
            stage.detail(),
            streamability
        );
    }

    public static byte[] readBounded(Path file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("Input file is required");
        }
        Path path = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Input is not a regular file: " + path);
        }
        long size = Files.size(path);
        if (size > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException(
                "Input exceeds the " + MAX_INPUT_BYTES + " byte capture cap");
        }
        return Files.readAllBytes(path);
    }

    private static Path cwd() {
        return Path.of(System.getProperty("user.dir", "."));
    }

    private static String simpleName(Class<?> type) {
        String simple = type.getSimpleName();
        return simple == null || simple.isBlank() ? type.getName() : simple;
    }
}
