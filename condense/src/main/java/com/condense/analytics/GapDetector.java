package com.condense.analytics;

import com.condense.core.TrackingRepository;
import com.condense.core.TrackingRepository.RawCommandTokens;
import com.condense.persist.CondenseClock;
import com.condense.core.ProjectFingerprint;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects telemetry gap candidates: commands where output was substantive
 * ({@code raw_tokens > 100}) but filtering achieved less than 10% token savings.
 * Candidates are grouped by command prefix (first two tokens) and ordered by
 * total wasted tokens descending.
 */
@ApplicationScoped
public class GapDetector {

    private final TrackingRepository tracking;

    public GapDetector() {
        this(null);
    }

    @Inject
    public GapDetector(TrackingRepository tracking) {
        this.tracking = tracking;
    }

    @RegisterForReflection
    public record GapCandidate(
        @JsonProperty("command_prefix") String commandPrefix,
        @JsonProperty("invocations") long invocations,
        @JsonProperty("total_raw_tokens") long totalRawTokens,
        @JsonProperty("total_filtered_tokens") long totalFilteredTokens,
        @JsonProperty("wasted_tokens") long wastedTokens,
        @JsonProperty("savings_pct") double savingsPct
    ) {}

    /**
     * Extracts the first two whitespace-separated tokens of a command string,
     * or the single token if only one exists.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "mvn test -Dtest=Foo"} &rarr; {@code "mvn test"}</li>
     *   <li>{@code "git log --oneline"} &rarr; {@code "git log"}</li>
     *   <li>{@code "pytest"} &rarr; {@code "pytest"}</li>
     * </ul>
     */
    public static String extractPrefix(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String[] parts = command.trim().split("\\s+");
        if (parts.length >= 2) {
            return parts[0] + " " + parts[1];
        }
        return parts[0];
    }

    /**
     * Queries commands with &lt;10% savings and &gt;100 raw tokens, groups them by
     * prefix, and returns the top gap candidates sorted by total wasted tokens descending.
     *
     * @param scope     "global" or "project"
     * @param sinceDays number of past days to include; 0 = all time
     * @param limit     maximum number of candidates to return (defaults to 10 if &le; 0)
     * @return sorted list of gap candidates; never null
     */
    public List<GapCandidate> findGaps(String scope, int sinceDays, int limit) {
        if (tracking == null) {
            return List.of();
        }

        int effectiveLimit = limit <= 0 ? 10 : limit;
        String projectHash = "project".equalsIgnoreCase(scope)
            ? ProjectFingerprint.ofCurrentDir()
            : null;
        long sinceEpoch = sinceDays <= 0
            ? 0L
            : (CondenseClock.epochSeconds() - (long) sinceDays * 86400L);

        List<RawCommandTokens> rows = tracking.queryLowSavingsCommands(sinceEpoch, projectHash);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        Map<String, Accumulator> grouped = new LinkedHashMap<>();
        for (RawCommandTokens row : rows) {
            String prefix = extractPrefix(row.command());
            if (prefix.isBlank()) {
                continue;
            }
            Accumulator acc = grouped.computeIfAbsent(prefix, k -> new Accumulator());
            acc.count++;
            acc.sumRaw += row.rawTokens();
            acc.sumOut += row.outTokens();
        }

        List<GapCandidate> candidates = new ArrayList<>(grouped.size());
        for (Map.Entry<String, Accumulator> entry : grouped.entrySet()) {
            Accumulator acc = entry.getValue();
            long wasted = acc.sumOut;
            double pct = acc.sumRaw == 0
                ? 0.0
                : Math.round(((acc.sumRaw - acc.sumOut) * 1000.0) / acc.sumRaw) / 10.0;
            candidates.add(new GapCandidate(
                entry.getKey(),
                acc.count,
                acc.sumRaw,
                acc.sumOut,
                wasted,
                pct
            ));
        }

        candidates.sort(
            Comparator.comparingLong(GapCandidate::wastedTokens).reversed()
                .thenComparing(Comparator.comparingLong(GapCandidate::invocations).reversed())
                .thenComparing(GapCandidate::commandPrefix)
        );

        if (candidates.size() > effectiveLimit) {
            return candidates.subList(0, effectiveLimit);
        }
        return candidates;
    }

    private static final class Accumulator {
        long count = 0;
        long sumRaw = 0;
        long sumOut = 0;
    }
}
