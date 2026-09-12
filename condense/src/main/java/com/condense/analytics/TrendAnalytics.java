package com.condense.analytics;

import com.condense.core.ProjectFingerprint;
import com.condense.core.TrackingRepository;
import com.condense.core.TrackingRepository.WeeklyStat;
import com.condense.persist.CondenseClock;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Computes week-over-week token savings trends, filling missing weeks with zero values
 * to preserve a continuous timeline.
 */
@ApplicationScoped
public class TrendAnalytics {

    private final TrackingRepository tracking;

    public TrendAnalytics() {
        this(null);
    }

    @Inject
    public TrendAnalytics(TrackingRepository tracking) {
        this.tracking = tracking;
    }

    @RegisterForReflection
    public record WeekSummary(
        @JsonProperty("week") String week,
        @JsonProperty("commands") long commands,
        @JsonProperty("raw_tokens") long rawTokens,
        @JsonProperty("filtered_tokens") long filteredTokens,
        @JsonProperty("tokens_saved") long tokensSaved,
        @JsonProperty("savings_pct") double savingsPct,
        @JsonProperty("compression_ratio") double compressionRatio
    ) {}

    @RegisterForReflection
    public record TrendReport(
        @JsonProperty("scope") String scope,
        @JsonProperty("weeks_requested") int weeksRequested,
        @JsonProperty("total_commands") long totalCommands,
        @JsonProperty("total_raw_tokens") long totalRawTokens,
        @JsonProperty("total_filtered_tokens") long totalFilteredTokens,
        @JsonProperty("total_tokens_saved") long totalTokensSaved,
        @JsonProperty("overall_savings_pct") double overallSavingsPct,
        @JsonProperty("overall_compression_ratio") double overallCompressionRatio,
        @JsonProperty("weeks") List<WeekSummary> weeks
    ) {}

    /**
     * Builds a continuous week-over-week trend report for the given scope and number of weeks.
     *
     * @param scope "global" or "project"
     * @param weeks number of past weeks to include (e.g. 8)
     * @return assembled TrendReport with all weeks present
     */
    public TrendReport buildTrendReport(String scope, int weeks) {
        int effectiveWeeks = Math.max(1, weeks);
        String projectHash = resolveProjectHash(scope);

        List<WeeklyStat> queryStats = tracking != null ? tracking.queryWeekly(effectiveWeeks, projectHash) : List.of();
        Map<String, WeeklyStat> statMap = new LinkedHashMap<>();
        for (WeeklyStat s : queryStats) {
            statMap.put(s.week(), s);
        }

        LocalDate today = Instant.ofEpochSecond(CondenseClock.epochSeconds()).atZone(ZoneOffset.UTC).toLocalDate();
        List<String> weekKeys = new ArrayList<>(effectiveWeeks);
        for (int i = effectiveWeeks - 1; i >= 0; i--) {
            weekKeys.add(formatWeek(today.minusWeeks(i)));
        }

        long totalCommands = 0;
        long totalRaw = 0;
        long totalFiltered = 0;
        long totalSaved = 0;

        List<WeekSummary> summaries = new ArrayList<>(effectiveWeeks);
        for (String weekKey : weekKeys) {
            WeeklyStat stat = statMap.get(weekKey);
            if (stat != null) {
                long cmds = stat.count();
                long raw = stat.sumRaw();
                long filtered = stat.sumOut();
                long saved = Math.max(0, raw - filtered);
                double savingsPct = raw > 0 ? (100.0 * saved / raw) : 0.0;
                double ratio = filtered > 0 ? ((double) raw / filtered) : (raw > 0 ? (double) raw : 1.0);

                totalCommands += cmds;
                totalRaw += raw;
                totalFiltered += filtered;
                totalSaved += saved;

                summaries.add(new WeekSummary(
                    weekKey,
                    cmds,
                    raw,
                    filtered,
                    saved,
                    Math.round(savingsPct * 10.0) / 10.0,
                    Math.round(ratio * 10.0) / 10.0
                ));
            } else {
                summaries.add(new WeekSummary(
                    weekKey,
                    0L,
                    0L,
                    0L,
                    0L,
                    0.0,
                    1.0
                ));
            }
        }

        double overallSavingsPct = totalRaw > 0 ? (100.0 * totalSaved / totalRaw) : 0.0;
        double overallRatio = totalFiltered > 0 ? ((double) totalRaw / totalFiltered) : (totalRaw > 0 ? (double) totalRaw : 1.0);

        return new TrendReport(
            scope == null ? "global" : scope,
            effectiveWeeks,
            totalCommands,
            totalRaw,
            totalFiltered,
            totalSaved,
            Math.round(overallSavingsPct * 10.0) / 10.0,
            Math.round(overallRatio * 10.0) / 10.0,
            Collections.unmodifiableList(summaries)
        );
    }

    /**
     * Formats a LocalDate into SQLite's %Y-W%W format (year and 2-digit week of year
     * starting on Monday, with days before first Monday in week 00).
     */
    public static String formatWeek(LocalDate date) {
        LocalDate firstDay = LocalDate.of(date.getYear(), 1, 1);
        int daysToMonday = (DayOfWeek.MONDAY.getValue() - firstDay.getDayOfWeek().getValue() + 7) % 7;
        LocalDate firstMonday = firstDay.plusDays(daysToMonday);
        int week;
        if (date.isBefore(firstMonday)) {
            week = 0;
        } else {
            week = 1 + (int) (ChronoUnit.DAYS.between(firstMonday, date) / 7);
        }
        return String.format(Locale.ROOT, "%04d-W%02d", date.getYear(), week);
    }

    private String resolveProjectHash(String scope) {
        return "project".equalsIgnoreCase(scope)
            ? ProjectFingerprint.ofCurrentDir()
            : null;
    }
}
