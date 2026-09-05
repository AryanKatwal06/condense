package com.condense.core;

import com.condense.ir.Documents;
import com.condense.ir.JsonRenderer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared proxy engine for the CLI root command and the MCP {@code run} tool.
 *
 * <p>Does not close {@link TrackingRepository} — the CLI process exits after one
 * run; the MCP server stays up.
 */
@ApplicationScoped
public class ProxyService {

    private final CommandExecutor executor;
    private final StrategyRegistry registry;
    private final TrackingRepository tracking;
    private final TeeWriter teeWriter;
    private final ConfigLoader configLoader;

    public ProxyService() {
        this(null, null, null, null, null);
    }

    @Inject
    public ProxyService(
            CommandExecutor executor,
            StrategyRegistry registry,
            TrackingRepository tracking,
            TeeWriter teeWriter,
            ConfigLoader configLoader
    ) {
        this.executor = executor;
        this.registry = registry;
        this.tracking = tracking;
        this.teeWriter = teeWriter;
        this.configLoader = configLoader;
    }

    public record Outcome(
        ExecutionResult result,
        FilterResult filtered,
        Path teePath,
        boolean alreadyPrinted
    ) {}

    /**
     * Look up a filter, run or apply it, attach a schema-1 document, optionally
     * print, then record analytics. Child exit code is unchanged.
     *
     * @param out {@code null} skips all printing (MCP uses this so only JSON-RPC
     *            lines hit process stdout)
     */
    public Outcome run(
            List<String> args,
            int verbose,
            boolean ultraCompact,
            boolean json,
            PrintStream out,
            PrintStream err
    ) throws Exception {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("command is required");
        }
        List<String> argList = List.copyOf(args);
        String commandStr = String.join(" ", argList);
        FilterStrategy strategy = registry.lookup(argList.toArray(new String[0]));
        CondenseConfig config = configLoader.load();
        ExecutionResult result;
        FilterResult filtered;
        boolean alreadyPrinted = false;
        PrintStream liveErr = err == null ? System.err : err;
        PrintStream liveOut = out == null
            ? new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)
            : out;
        if (StreamingProxy.shouldStream(strategy, commandStr)) {
            StreamingProxy.StreamedRun streamed = StreamingProxy.run(
                executor, strategy, argList, commandStr, config,
                verbose, ultraCompact, liveOut, liveErr, json);
            result = streamed.result();
            filtered = streamed.filtered();
            alreadyPrinted = streamed.alreadyPrinted();
        } else {
            result = executor.execute(argList, CommandExecutor.resolveProxyTimeout());
            filtered = strategy.apply(
                commandStr, result, config, verbose, ultraCompact);
        }

        Path teePath = teeWriter.maybeDump(commandStr, result);

        if (filtered.document() == null) {
            filtered = filtered.withDocument(Documents.fromResult(
                commandStr, strategy.getClass().getSimpleName(), result, filtered));
        }

        if (out != null) {
            if (json) {
                String jsonText = JsonRenderer.render(filtered.document());
                out.print(jsonText);
                if (!jsonText.endsWith("\n")) {
                    out.println();
                }
                filtered = filtered.withRenderedOutput(jsonText);
            } else if (!alreadyPrinted) {
                out.print(filtered.output());
                if (!filtered.output().endsWith("\n")) {
                    out.println();
                }
            }
            if (teePath != null) {
                out.println("[raw output saved to: " + teePath + "]");
            }
            out.flush();
        }

        record(commandStr, filtered, result);
        return new Outcome(result, filtered, teePath, alreadyPrinted);
    }

    private void record(String commandStr, FilterResult filtered, ExecutionResult result) {
        if (tracking == null) {
            return;
        }
        try {
            String project = ProjectFingerprint.ofCurrentDir();
            tracking.insert(
                commandStr,
                project,
                System.getProperty("user.dir"),
                filtered.rawTokens(),
                filtered.outTokens(),
                result.durationMs()
            );
            tracking.insertOutcomes(commandStr, project, filtered.incidents());
        } catch (Exception ignored) {
            // fail-open — the filtered result is already built
        }
    }
}
