package com.condense.filter.stage;

import com.condense.filter.pipeline.CollectingSink;
import com.condense.filter.pipeline.EmissionSink;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.StageSession;
import com.condense.filter.pipeline.Streamability;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.ir.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NpmInstallSummaryStage implements FilterStage {
    public static final NpmInstallSummaryStage INSTANCE = new NpmInstallSummaryStage();
    private static final Pattern ADDED_PATTERN = Pattern.compile("added (\\d+) packages?");
    private static final Pattern AUDIT_PATTERN = Pattern.compile("found (\\d+) vulnerabilit");
    private static final Pattern IRREVOCABLE = Pattern.compile("(?i)^npm (warn\\b|err!)");
    private static final int MAX_IRREVOCABLE = 20;

    private NpmInstallSummaryStage() {}

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
        private String added;
        private String audit;
        private Integer vulnerabilityCount;
        private final List<String> irrevocableLines = new ArrayList<>();
        private int irrevocable;
        private boolean emittedAny;

        @Override
        public void feedLine(String line, EmissionSink sink, FilterContext context) {
            String value = line != null ? line : "";
            if (BoundedRegex.matcher(IRREVOCABLE, value).find() && irrevocable < MAX_IRREVOCABLE) {
                sink.emit(value);
                irrevocableLines.add(value);
                irrevocable++;
                emittedAny = true;
            }
            Matcher addedMatch = BoundedRegex.matcher(ADDED_PATTERN, value);
            if (addedMatch.find()) {
                added = addedMatch.group(1);
            }
            Matcher auditMatch = BoundedRegex.matcher(AUDIT_PATTERN, value);
            if (auditMatch.find()) {
                audit = auditMatch.group(0);
                vulnerabilityCount = Integer.parseInt(auditMatch.group(1));
            }
        }

        @Override
        public void endOfInput(EmissionSink sink, FilterContext context) {
            int exit = context != null && context.result() != null ? context.result().exitCode() : 0;
            Integer addedPackages = added == null ? null : Integer.parseInt(added);
            boolean failed = exit != 0;
            if (failed) {
                if (emittedAny) {
                    sink.emit("npm install failed");
                }
                publish(context, addedPackages, failed);
                return;
            }
            StringBuilder sb = new StringBuilder("✓ npm install");
            if (added != null) {
                sb.append(": ").append(added).append(" packages");
            }
            if (audit != null) {
                sb.append(" | ").append(audit);
            }
            sink.emit(sb.toString());
            publish(context, addedPackages, false);
        }

        private void publish(FilterContext context, Integer addedPackages, boolean failed) {
            if (context == null || context.documentBuilder() == null) {
                return;
            }
            context.documentBuilder().dependency(new Document.DependencyDocument(
                addedPackages,
                vulnerabilityCount,
                audit == null ? "" : audit,
                List.copyOf(irrevocableLines),
                failed
            ));
        }
    }
}
