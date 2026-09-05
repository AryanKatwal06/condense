package com.condense.core;

import com.condense.filter.pipeline.EmissionSink;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.LineDiff;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.PipelineMode;
import com.condense.filter.pipeline.StageSession;
import com.condense.ir.Document;
import com.condense.ir.Documents;
import com.condense.trust.Provenance;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Live-print runner. Mode is derived from the pipeline, never from a command table.
 */
public final class StreamingProxy {

    private static final Logger log = Logger.getLogger(StreamingProxy.class);

    private StreamingProxy() {}

    public static boolean shouldStream(FilterStrategy strategy, String command) {
        if (strategy instanceof PassthroughStrategy) {
            return true;
        }
        if (strategy instanceof PipelineBackedFilter pipelineFilter) {
            return pipelineFilter.resolveActivePipeline(command).mode() == PipelineMode.STREAM;
        }
        return false;
    }

    public record StreamedRun(FilterResult filtered, ExecutionResult result, boolean alreadyPrinted) {}

    public static StreamedRun run(
            CommandExecutor executor,
            FilterStrategy strategy,
            List<String> args,
            String command,
            CondenseConfig config,
            int verbose,
            boolean ultraCompact,
            PrintStream out,
            PrintStream err
    ) throws IOException, InterruptedException {
        return run(executor, strategy, args, command, config, verbose, ultraCompact, out, err, false);
    }

    public static StreamedRun run(
            CommandExecutor executor,
            FilterStrategy strategy,
            List<String> args,
            String command,
            CondenseConfig config,
            int verbose,
            boolean ultraCompact,
            PrintStream out,
            PrintStream err,
            boolean suppressLivePrint
    ) throws IOException, InterruptedException {
        if (strategy instanceof PassthroughStrategy) {
            return runRaw(executor, args, command, out, err, suppressLivePrint);
        }
        if (!(strategy instanceof PipelineBackedFilter pipelineFilter)) {
            throw new IllegalStateException("StreamingProxy received a non-pipeline filter");
        }
        String filterName = strategy.getClass().getSimpleName();
        FilterPipeline pipeline = pipelineFilter.resolveActivePipeline(command);
        PrintStream liveOut = suppressLivePrint
            ? new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)
            : out;
        LiveSession live = new LiveSession(pipeline, command, config, verbose, ultraCompact, liveOut);
        ExecutionResult result = executor.execute(args, CommandExecutor.resolveProxyTimeout(), live);
        live.finishDecoders();
        if (live.capped && err != null && !err.checkError()) {
            err.println("condense: output capped at 10MB");
        }
        FilterResult gate = pipelineFilter.evaluateGate(command, result, config, verbose, ultraCompact);
        if (gate != null && !live.emittedAny) {
            FilterResult gated = gate.withDocument(Documents.fromResult(command, filterName, result, gate));
            if (!suppressLivePrint) {
                out.print(gated.output());
                if (!gated.output().endsWith("\n")) {
                    out.println();
                }
            }
            return new StreamedRun(gated, result, !suppressLivePrint);
        }
        FilterContext finalCtx = FilterContext.of(command, result, config, verbose, ultraCompact);
        live.endOfInput(finalCtx);
        String body = live.collected();
        Document document = Documents.fromContext(finalCtx, command, filterName, result, true, body);
        List<com.condense.filter.pipeline.FilterIncident> incidents = finalCtx.incidents().stream()
            .map(incident -> incident.withFilterName(filterName))
            .toList();
        String stamped = live.stamped
            ? Provenance.STAMP + (body.isEmpty() ? "" : "\n" + body)
            : Provenance.stamp(body);
        FilterResult filtered = new FilterResult(
            stamped, tokenCount(result), TokenCounter.count(stamped), true, incidents, document);
        return new StreamedRun(filtered, result, suppressLivePrint ? false : (live.emittedAny || live.stamped));
    }

    /**
     * Same session chain as {@link #run} without a child process. Package-visible
     * so stream-vs-capture tests cannot invent a second walker.
     */
    static String replay(FilterPipeline pipeline, String text, FilterContext completed) {
        ByteArrayOutputStream discarded = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(discarded, true, StandardCharsets.UTF_8);
        LiveSession live = new LiveSession(
            pipeline,
            completed.command(),
            completed.config(),
            completed.verbose(),
            completed.ultraCompact(),
            out
        );
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) {
            live.onStdout(bytes, bytes.length);
        }
        live.finishDecoders();
        live.endOfInput(completed);
        String body = live.collected();
        return live.stamped
            ? Provenance.STAMP + (body.isEmpty() ? "" : "\n" + body)
            : Provenance.stamp(body);
    }

    private static StreamedRun runRaw(
            CommandExecutor executor,
            List<String> args,
            String command,
            PrintStream out,
            PrintStream err,
            boolean suppressLivePrint
    ) throws IOException, InterruptedException {
        PrintStream liveOut = suppressLivePrint
            ? new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)
            : out;
        RawSession raw = new RawSession(liveOut);
        ExecutionResult result = executor.execute(args, CommandExecutor.resolveProxyTimeout(), raw);
        raw.finish();
        if (raw.capped && err != null && !err.checkError()) {
            err.println("condense: output capped at 10MB");
        }
        FilterResult passthrough = FilterResult.passthrough(result)
            .withDocument(Documents.fromResult(command, "passthrough", result, FilterResult.passthrough(result)));
        return new StreamedRun(passthrough, result, !suppressLivePrint);
    }

    private static int tokenCount(ExecutionResult result) {
        int tokens = 0;
        try {
            if (result.stdoutFile() != null) {
                tokens += TokenCounter.count(result.stdoutFile());
            }
            if (result.stderrFile() != null) {
                tokens += TokenCounter.count(result.stderrFile());
            }
        } catch (Exception e) {
            log.debugf("Token counting failed in streaming proxy: %s", e.getMessage());
        }
        return tokens;
    }

    private static final class RawSession implements StreamListener {
        private final PrintStream out;
        private final Utf8LineDecoder stdout;
        private final Utf8LineDecoder stderr;
        private volatile boolean capped;

        RawSession(PrintStream out) {
            this.out = out;
            this.stdout = new Utf8LineDecoder(this::print);
            this.stderr = new Utf8LineDecoder(this::print);
        }

        private synchronized void print(String line) {
            if (out.checkError()) {
                return;
            }
            out.println(Provenance.passthrough(line));
        }

        @Override
        public void onStdout(byte[] chunk, int length) {
            stdout.feed(chunk, 0, length);
        }

        @Override
        public void onStderr(byte[] chunk, int length) {
            stderr.feed(chunk, 0, length);
        }

        @Override
        public void onCapped() {
            capped = true;
        }

        void finish() {
            stdout.finish();
            stderr.finish();
        }
    }

    private static final class LiveSession implements StreamListener {
        private final PrintStream out;
        private final List<StageSession> sessions;
        private final List<EmissionSink> sinks;
        private final Utf8LineDecoder stdout;
        private final Utf8LineDecoder stderr;
        private final StringBuilder collected = new StringBuilder();
        private FilterContext context;
        private boolean stamped;
        private boolean emittedAny;
        private volatile boolean capped;

        LiveSession(
                FilterPipeline pipeline,
                String command,
                CondenseConfig config,
                int verbose,
                boolean ultraCompact,
                PrintStream out
        ) {
            this.out = out;
            this.context = FilterContext.of(command, null, config, verbose, ultraCompact);
            this.sessions = new ArrayList<>();
            for (FilterStage stage : pipeline.stages()) {
                sessions.add(stage.openSession());
            }
            this.sinks = new ArrayList<>();
            PrintSink print = new PrintSink();
            if (sessions.isEmpty()) {
                sinks.add(print);
            } else {
                EmissionSink[] outs = new EmissionSink[sessions.size()];
                outs[sessions.size() - 1] = print;
                for (int i = sessions.size() - 2; i >= 0; i--) {
                    outs[i] = new ForwardingSink(sessions.get(i + 1), outs[i + 1]);
                }
                sinks.addAll(List.of(outs));
            }
            this.stdout = new Utf8LineDecoder(this::feedLine);
            this.stderr = new Utf8LineDecoder(this::feedLine);
        }

        private synchronized void feedLine(String line) {
            try {
                if (sessions.isEmpty()) {
                    sinks.get(0).emit(line);
                    return;
                }
                sessions.get(0).feedLine(line, sinks.get(0), context);
            } catch (Exception e) {
                log.warnf("Streaming stage failed: %s", e.getMessage());
                context.recordIncident(
                    com.condense.filter.pipeline.FilterIncident.stageException("stream", e.getMessage()));
            }
        }

        synchronized void endOfInput(FilterContext completed) {
            this.context = completed;
            try {
                if (sessions.isEmpty()) {
                    return;
                }
                for (int i = 0; i < sessions.size(); i++) {
                    sessions.get(i).endOfInput(sinks.get(i), completed);
                }
            } catch (Exception e) {
                log.warnf("Streaming finalize failed: %s", e.getMessage());
                completed.recordIncident(
                    com.condense.filter.pipeline.FilterIncident.stageException("stream", e.getMessage()));
            }
        }

        void finishDecoders() {
            stdout.finish();
            stderr.finish();
        }

        String collected() {
            return collected.toString();
        }

        @Override
        public void onStdout(byte[] chunk, int length) {
            stdout.feed(chunk, 0, length);
        }

        @Override
        public void onStderr(byte[] chunk, int length) {
            stderr.feed(chunk, 0, length);
        }

        @Override
        public void onCapped() {
            capped = true;
        }

        private final class ForwardingSink implements EmissionSink {
            private final StageSession next;
            private final EmissionSink nextSink;
            private boolean shortCircuited;

            ForwardingSink(StageSession next, EmissionSink nextSink) {
                this.next = next;
                this.nextSink = nextSink;
            }

            @Override
            public void emit(String line) {
                next.feedLine(line, nextSink, context);
            }

            @Override
            public void emitDocument(String text) {
                String raw = text == null ? "" : text;
                if (raw.isEmpty()) {
                    return;
                }
                for (String line : LineDiff.split(raw)) {
                    next.feedLine(line, nextSink, context);
                    if (nextSink.isShortCircuited()) {
                        shortCircuited = true;
                        return;
                    }
                }
            }

            @Override
            public void shortCircuit() {
                shortCircuited = true;
                nextSink.shortCircuit();
            }

            @Override
            public boolean isShortCircuited() {
                return shortCircuited || nextSink.isShortCircuited();
            }
        }

        private final class PrintSink implements EmissionSink {
            private boolean shortCircuited;

            @Override
            public void emit(String line) {
                String value = Provenance.neutralize(line == null ? "" : line);
                if (!stamped) {
                    if (!out.checkError()) {
                        out.println(Provenance.STAMP);
                    }
                    stamped = true;
                }
                if (collected.length() > 0) {
                    collected.append('\n');
                }
                collected.append(value);
                if (!out.checkError()) {
                    out.println(value);
                }
                emittedAny = true;
            }

            @Override
            public void emitDocument(String text) {
                String raw = text == null ? "" : text;
                if (raw.isEmpty()) {
                    return;
                }
                for (String line : LineDiff.split(raw)) {
                    emit(line);
                }
            }

            @Override
            public void shortCircuit() {
                shortCircuited = true;
            }

            @Override
            public boolean isShortCircuited() {
                return shortCircuited;
            }
        }
    }
}
