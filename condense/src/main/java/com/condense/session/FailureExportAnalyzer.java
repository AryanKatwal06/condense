package com.condense.session;

import com.condense.core.Mappers;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Offline failure export analyzer utility.
 * Aggregates local, sanitized failure report JSON payloads with zero network calls.
 */
@RegisterForReflection
public final class FailureExportAnalyzer {

    private static final Logger log = Logger.getLogger(FailureExportAnalyzer.class);

    @RegisterForReflection
    public record AnalysisSummary(
        int totalReports,
        int unparseableFiles,
        Map<String, Integer> stageBreakdown,
        Map<String, Integer> categoryBreakdown,
        Map<Integer, Integer> exitCodeBreakdown,
        Map<String, Integer> versionBreakdown,
        Map<String, Integer> durationBuckets,
        Map<String, Integer> outputLengthBuckets
    ) {
        public String renderText() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Condense Failure Export Analysis ===\n");
            sb.append("Total Reports Analyzed: ").append(totalReports);
            if (unparseableFiles > 0) {
                sb.append(" (").append(unparseableFiles).append(" invalid/skipped files)");
            }
            sb.append("\n\n");

            if (totalReports == 0) {
                sb.append("No valid failure reports found in target.\n");
                return sb.toString();
            }

            sb.append("Failure Stages:\n");
            stageBreakdown.forEach((stage, count) ->
                sb.append(String.format("  %-24s : %d%n", stage, count)));
            sb.append("\n");

            sb.append("Error Categories:\n");
            categoryBreakdown.forEach((category, count) ->
                sb.append(String.format("  %-24s : %d%n", category, count)));
            sb.append("\n");

            sb.append("Exit Codes:\n");
            exitCodeBreakdown.forEach((code, count) ->
                sb.append(String.format("  %-24s : %d%n", String.valueOf(code), count)));
            sb.append("\n");

            sb.append("Condense Versions:\n");
            versionBreakdown.forEach((ver, count) ->
                sb.append(String.format("  %-24s : %d%n", ver, count)));
            sb.append("\n");

            sb.append("Duration Buckets:\n");
            durationBuckets.forEach((bucket, count) ->
                sb.append(String.format("  %-24s : %d%n", bucket, count)));
            sb.append("\n");

            sb.append("Output Length Buckets:\n");
            outputLengthBuckets.forEach((bucket, count) ->
                sb.append(String.format("  %-24s : %d%n", bucket, count)));

            return sb.toString().trim();
        }
    }

    public AnalysisSummary analyze(Path path) {
        if (path == null || !Files.exists(path)) {
            return emptySummary();
        }

        Map<String, Integer> stages = new TreeMap<>();
        Map<String, Integer> categories = new TreeMap<>();
        Map<Integer, Integer> exitCodes = new TreeMap<>();
        Map<String, Integer> versions = new TreeMap<>();
        Map<String, Integer> durations = new LinkedHashMap<>();
        Map<String, Integer> lengths = new LinkedHashMap<>();

        int[] totals = new int[2]; // [0] = valid, [1] = unparseable

        if (Files.isRegularFile(path)) {
            processFile(path, stages, categories, exitCodes, versions, durations, lengths, totals);
        } else if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.list(path)) {
                stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> processFile(p, stages, categories, exitCodes, versions, durations, lengths, totals));
            } catch (IOException e) {
                log.warnf("Failed to list files in %s: %s", path, e.getMessage());
            }
        }

        return new AnalysisSummary(
            totals[0],
            totals[1],
            Collections.unmodifiableMap(stages),
            Collections.unmodifiableMap(categories),
            Collections.unmodifiableMap(exitCodes),
            Collections.unmodifiableMap(versions),
            Collections.unmodifiableMap(durations),
            Collections.unmodifiableMap(lengths)
        );
    }

    private void processFile(
            Path file,
            Map<String, Integer> stages,
            Map<String, Integer> categories,
            Map<Integer, Integer> exitCodes,
            Map<String, Integer> versions,
            Map<String, Integer> durations,
            Map<String, Integer> lengths,
            int[] totals
    ) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            FailureReportPayload payload = Mappers.JSON.readValue(bytes, FailureReportPayload.class);
            if (payload == null || payload.errorCategory() == null) {
                totals[1]++;
                return;
            }

            totals[0]++;
            stages.merge(payload.failureStage() != null ? payload.failureStage() : "UNKNOWN", 1, Integer::sum);
            categories.merge(payload.errorCategory(), 1, Integer::sum);
            exitCodes.merge(payload.exitCode(), 1, Integer::sum);
            versions.merge(payload.condenseVersion() != null ? payload.condenseVersion() : "unknown", 1, Integer::sum);
            durations.merge(payload.durationBucket() != null ? payload.durationBucket() : "unknown", 1, Integer::sum);
            lengths.merge(payload.outputLengthBucket() != null ? payload.outputLengthBucket() : "unknown", 1, Integer::sum);
        } catch (Exception e) {
            log.debugf("Skipping unparseable failure report %s: %s", file, e.getMessage());
            totals[1]++;
        }
    }

    private static AnalysisSummary emptySummary() {
        return new AnalysisSummary(
            0,
            0,
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap()
        );
    }
}
