package com.zapproxy.analytics;

import com.zapproxy.core.ProjectFingerprint;
import com.zapproxy.core.TrackingRepository;
import com.zapproxy.core.TrackingRepository.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Assembles {@link GainReport} instances from raw {@link TrackingRepository} queries.
 *
 * <p>Handles scope resolution (global vs project) and time-window calculation,
 * keeping all SQL behind {@link TrackingRepository} and all formatting behind
 * {@link GainCommand} and {@link AsciiGraphRenderer}.
 */
@ApplicationScoped
public class GainRepository {

    @Inject
    TrackingRepository tracking;

    /**
     * Builds a {@link GainReport} for the given scope and time window.
     *
     * @param scope     "global" or "project"
     * @param sinceDays number of past days to include; 0 = all time
     * @param topN      number of top commands to include in the report
     * @return assembled report; never null
     */
    public GainReport buildReport(String scope, int sinceDays, int topN) {
        String projectHash = resolveProjectHash(scope);
        long sinceEpoch = sinceEpoch(sinceDays);

        AggregateStats agg = tracking.queryAggregate(sinceEpoch, projectHash);
        List<TopCommand> top = tracking.queryTopCommands(topN, sinceEpoch, projectHash);
        List<DailyStat> daily = tracking.queryDaily(sinceDays == 0 ? 30 : sinceDays, projectHash);

        return new GainReport(
            scope,
            sinceDays,
            agg.totalCommands(),
            agg.sumRaw(),
            agg.sumOut(),
            agg.tokensSaved(),
            agg.savingsPct(),
            agg.sumExecMs(),
            agg.avgExecMs(),
            top,
            daily
        );
    }

    /** Returns daily stats for bar chart rendering. */
    public List<DailyStat> dailyStats(int days, String scope) {
        return tracking.queryDaily(days, resolveProjectHash(scope));
    }

    /** Returns weekly stats for table rendering. */
    public List<WeeklyStat> weeklyStats(int weeks, String scope) {
        return tracking.queryWeekly(weeks, resolveProjectHash(scope));
    }

    /** Returns the last N command executions. */
    public List<RecentCommand> recentCommands(int limit, String scope) {
        return tracking.queryRecent(limit, resolveProjectHash(scope));
    }

    /** Returns top N commands by tokens saved. */
    public List<TopCommand> topCommands(int n, int sinceDays, String scope) {
        return tracking.queryTopCommands(n, sinceEpoch(sinceDays), resolveProjectHash(scope));
    }

    public void close() {
        tracking.close();
    }

    // ── private ──────────────────────────────────────────────────────────────

    private String resolveProjectHash(String scope) {
        return "project".equalsIgnoreCase(scope)
            ? ProjectFingerprint.ofCurrentDir()
            : null;
    }

    private long sinceEpoch(int sinceDays) {
        if (sinceDays <= 0) return 0L;
        return System.currentTimeMillis() / 1000L - (long) sinceDays * 86400L;
    }
}
