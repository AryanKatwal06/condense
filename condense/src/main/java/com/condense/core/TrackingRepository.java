package com.condense.core;

import com.condense.filter.pipeline.FilterIncident;
import com.condense.persist.BackupRetention;
import com.condense.persist.RetentionPolicy;
import com.condense.persist.SchemaMigrator;
import com.condense.persist.TeeRetention;
import com.condense.persist.WriteFailureLedger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@ApplicationScoped
public class TrackingRepository {

    private static final Logger log = Logger.getLogger(TrackingRepository.class);

    static final int SQLITE_BUSY = 5;
    static final int SQLITE_LOCKED = 6;
    private static final int INSERT_BUSY_RETRIES = 2;

    private static final String INSERT = """
        INSERT INTO commands(ts, command, project, cwd, raw_tokens, out_tokens, exec_ms)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String INSERT_OUTCOME = """
        INSERT INTO filter_outcomes(ts, command, project, filter_name, kind, stage_name, fallback_succeeded, detail)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String INSERT_HOOK_EVENT = """
        INSERT INTO hook_events(ts, tool, action, path, sha256, success, detail)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPSERT_HOOK_BASELINE = """
        INSERT INTO hook_baselines(tool, path, sha256, installed_ts)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(tool) DO UPDATE SET path = excluded.path, sha256 = excluded.sha256, installed_ts = excluded.installed_ts
        """;

    private final PlatformDirs platformDirs;

    private Connection connection;
    private volatile boolean degraded = false;
    private volatile boolean migrateFailed = false;
    private volatile boolean schemaAhead = false;
    private volatile int lastSchemaVersion = -1;
    private volatile String lastJournalMode = "";
    private volatile TeeRetention.SweepResult lastTeeSweep = TeeRetention.SweepResult.empty();

    @Inject
    public TrackingRepository(PlatformDirs platformDirs) {
        this.platformDirs = platformDirs;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public boolean isMigrateFailed() {
        return migrateFailed;
    }

    public boolean isSchemaAhead() {
        return schemaAhead;
    }

    public int schemaVersion() {
        try {
            return SchemaMigrator.readUserVersion(connection());
        } catch (SQLException e) {
            this.degraded = true;
            log.warnf(e, "Failed to read schema version: %s", e.getMessage());
            return lastSchemaVersion;
        }
    }

    public String journalMode() {
        if (lastJournalMode != null && !lastJournalMode.isBlank()) {
            return lastJournalMode;
        }
        try {
            lastJournalMode = SchemaMigrator.readJournalMode(connection());
            return lastJournalMode;
        } catch (SQLException e) {
            log.warnf(e, "Failed to read journal_mode: %s", e.getMessage());
            return lastJournalMode == null ? "" : lastJournalMode;
        }
    }

    public boolean databaseFileExists() {
        return Files.exists(platformDirs.resolveDataDir().resolve("condense.db"));
    }

    public TeeRetention.SweepResult lastTeeSweep() {
        return lastTeeSweep;
    }

    /**
     * Records a command execution.
     *
     * @param command    full command string, e.g. "git status"
     * @param project    12-char hex fingerprint of the project directory
     * @param cwd        absolute path of the working directory
     * @param rawTokens  estimated token count of the raw output
     * @param outTokens  estimated token count of the filtered output
     * @param execMs     wall-clock execution time in milliseconds
     */
    public void insert(String command, String project, String cwd,
                       int rawTokens, int outTokens, long execMs) {
        insertAt(System.currentTimeMillis() / 1000L, command, project, cwd, rawTokens, outTokens, execMs);
    }

    /** Package-visible so retention tests can plant expired rows. */
    void insertAt(long ts, String command, String project, String cwd,
                  int rawTokens, int outTokens, long execMs) {
        SQLException last = null;
        for (int attempt = 0; attempt <= INSERT_BUSY_RETRIES; attempt++) {
            try {
                try (PreparedStatement ps = connection().prepareStatement(INSERT)) {
                    ps.setLong(1, ts);
                    ps.setString(2, command);
                    ps.setString(3, project);
                    ps.setString(4, cwd);
                    ps.setInt(5, rawTokens);
                    ps.setInt(6, outTokens);
                    ps.setLong(7, execMs);
                    ps.executeUpdate();
                }
                return;
            } catch (SQLException e) {
                last = e;
                if (attempt < INSERT_BUSY_RETRIES && isBusyOrLocked(e)) {
                    sleepBackoff(attempt);
                    continue;
                }
                this.degraded = true;
                WriteFailureLedger.record(platformDirs.resolveDataDir(), e.getMessage());
                log.warnf(e, "Failed to record analytics for '%s': %s", command, e.getMessage());
                return;
            }
        }
        if (last != null) {
            this.degraded = true;
            WriteFailureLedger.record(platformDirs.resolveDataDir(), last.getMessage());
        }
    }

    static boolean isBusyOrLocked(SQLException e) {
        if (e == null) {
            return false;
        }
        int code = e.getErrorCode();
        if (code == SQLITE_BUSY || code == SQLITE_LOCKED) {
            return true;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return (message.contains("SQLITE_BUSY") || message.contains("SQLITE_LOCKED"))
            && !message.contains("SQLITE_READONLY");
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(25L << attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Persists fail-open filter incidents. Failures here do not mark the
     * commands ledger as degraded — token analytics must keep working.
     */
    public void insertOutcomes(String command, String project, List<FilterIncident> incidents) {
        if (incidents == null || incidents.isEmpty()) {
            return;
        }
        for (FilterIncident incident : incidents) {
            insertOutcome(command, project, incident);
        }
    }

    public void insertHookEvent(String tool, String action, String path, String sha256, boolean success, String detail) {
        try {
            try (PreparedStatement ps = connection().prepareStatement(INSERT_HOOK_EVENT)) {
                ps.setLong(1, System.currentTimeMillis() / 1000L);
                ps.setString(2, tool == null ? "" : tool);
                ps.setString(3, action == null ? "" : action);
                ps.setString(4, path);
                ps.setString(5, sha256);
                ps.setInt(6, success ? 1 : 0);
                ps.setString(7, detail);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warnf(e, "Failed to record hook event for '%s': %s", tool, e.getMessage());
        }
    }

    public void upsertHookBaseline(String tool, String path, String sha256) {
        if (tool == null || tool.isBlank() || path == null || sha256 == null) {
            return;
        }
        try {
            try (PreparedStatement ps = connection().prepareStatement(UPSERT_HOOK_BASELINE)) {
                ps.setString(1, tool);
                ps.setString(2, path);
                ps.setString(3, sha256);
                ps.setLong(4, System.currentTimeMillis() / 1000L);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warnf(e, "Failed to upsert hook baseline for '%s': %s", tool, e.getMessage());
        }
    }

    public void deleteHookBaseline(String tool) {
        if (tool == null || tool.isBlank()) {
            return;
        }
        try (PreparedStatement ps = connection().prepareStatement("DELETE FROM hook_baselines WHERE tool = ?")) {
            ps.setString(1, tool);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf(e, "Failed to delete hook baseline for '%s': %s", tool, e.getMessage());
        }
    }

    public HookBaseline findHookBaseline(String tool) {
        if (tool == null || tool.isBlank()) {
            return null;
        }
        try (PreparedStatement ps = connection().prepareStatement(
            "SELECT tool, path, sha256, installed_ts FROM hook_baselines WHERE tool = ?")) {
            ps.setString(1, tool);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new HookBaseline(
                        rs.getString("tool"),
                        rs.getString("path"),
                        rs.getString("sha256"),
                        rs.getLong("installed_ts"));
                }
            }
        } catch (SQLException e) {
            log.warnf(e, "Failed to read hook baseline for '%s': %s", tool, e.getMessage());
        }
        return null;
    }

    public long countHookEvents() {
        try (Statement st = connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM hook_events")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            log.warnf(e, "Failed to count hook events: %s", e.getMessage());
            return 0L;
        }
    }

    public void insertOutcome(String command, String project, FilterIncident incident) {
        if (incident == null) {
            return;
        }
        try {
            try (PreparedStatement ps = connection().prepareStatement(INSERT_OUTCOME)) {
                ps.setLong(1, System.currentTimeMillis() / 1000L);
                ps.setString(2, command);
                ps.setString(3, project);
                ps.setString(4, incident.filterName());
                ps.setString(5, incident.kind());
                ps.setString(6, incident.stageName());
                ps.setInt(7, incident.fallbackSucceeded() ? 1 : 0);
                ps.setString(8, incident.detail());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warnf(e, "Failed to record filter outcome for '%s': %s", command, e.getMessage());
        }
    }

    /** Used in tests and by {@code condense gain}. */
    public long countAll() {
        try (Statement st = connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM commands")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            this.degraded = true;
            log.warnf(e, "Failed to count commands: %s", e.getMessage());
            return 0L;
        }
    }

    public long countOutcomes() {
        try (Statement st = connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM filter_outcomes")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            log.warnf(e, "Failed to count filter outcomes: %s", e.getMessage());
            return 0L;
        }
    }

    public Long oldestCommandTs() {
        return commandTsBound("MIN(ts)");
    }

    public Long newestCommandTs() {
        return commandTsBound("MAX(ts)");
    }

    public Map<String, Long> outcomeCountsByKind() {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Statement st = connection().createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT kind, COUNT(*) AS total FROM filter_outcomes GROUP BY kind ORDER BY kind")) {
            while (rs.next()) {
                counts.put(rs.getString("kind"), rs.getLong("total"));
            }
        } catch (SQLException e) {
            log.warnf(e, "Failed to aggregate filter outcomes: %s", e.getMessage());
        }
        return counts;
    }

    /**
     * Returns aggregate statistics for all commands within the given time window.
     *
     * @param sinceEpoch  unix timestamp lower bound (inclusive); 0 = all time
     * @param projectHash 12-char project fingerprint to filter by; null = global
     * @return aggregate stats, never null
     */
    public AggregateStats queryAggregate(long sinceEpoch, String projectHash) {
        String sql = buildAggregateQuery(sinceEpoch, projectHash);
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            bindParams(ps, sinceEpoch, projectHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new AggregateStats(
                        rs.getLong("total_commands"),
                        rs.getLong("sum_raw"),
                        rs.getLong("sum_out"),
                        rs.getLong("sum_exec_ms")
                    );
                }
            }
        } catch (SQLException e) {
            this.degraded = true;
            log.warnf(e, "queryAggregate failed: %s", e.getMessage());
        }
        return new AggregateStats(0, 0, 0, 0);
    }

    public List<DailyStat> queryDaily(int days, String projectHash) {
        long since = System.currentTimeMillis() / 1000L - (long) days * 86400;
        String projectFilter = projectHash != null
            ? " AND project = ?" : "";
        String sql = """
            SELECT
                date(ts, 'unixepoch') AS day,
                COUNT(*)              AS total,
                SUM(raw_tokens)       AS sum_raw,
                SUM(out_tokens)       AS sum_out
            FROM commands
            WHERE ts >= ?
            """ + projectFilter + """
            GROUP BY day
            ORDER BY day ASC
            """;
        List<DailyStat> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setLong(1, since);
            if (projectHash != null) ps.setString(2, projectHash);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new DailyStat(
                        rs.getString("day"),
                        rs.getLong("total"),
                        rs.getLong("sum_raw"),
                        rs.getLong("sum_out")
                    ));
                }
            }
        } catch (SQLException e) {
            this.degraded = true;
            log.warnf(e, "queryDaily failed: %s", e.getMessage());
        }
        return result;
    }

    public List<WeeklyStat> queryWeekly(int weeks, String projectHash) {
        long since = System.currentTimeMillis() / 1000L - (long) weeks * 7 * 86400;
        String projectFilter = projectHash != null ? " AND project = ?" : "";
        String sql = """
            SELECT
                strftime('%Y-W%W', ts, 'unixepoch') AS week,
                COUNT(*)                             AS total,
                SUM(raw_tokens)                      AS sum_raw,
                SUM(out_tokens)                      AS sum_out
            FROM commands
            WHERE ts >= ?
            """ + projectFilter + """
            GROUP BY week
            ORDER BY week ASC
            """;
        List<WeeklyStat> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setLong(1, since);
            if (projectHash != null) ps.setString(2, projectHash);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new WeeklyStat(
                        rs.getString("week"),
                        rs.getLong("total"),
                        rs.getLong("sum_raw"),
                        rs.getLong("sum_out")
                    ));
                }
            }
        } catch (SQLException e) {
            this.degraded = true;
            log.warnf(e, "queryWeekly failed: %s", e.getMessage());
        }
        return result;
    }

    public List<TopCommand> queryTopCommands(int limit, long sinceEpoch, String projectHash) {
        String projectFilter = projectHash != null ? " AND project = ?" : "";
        String sql = """
            SELECT
                command,
                COUNT(*)              AS uses,
                SUM(raw_tokens)       AS sum_raw,
                SUM(out_tokens)       AS sum_out
            FROM commands
            WHERE ts >= ?
            """ + projectFilter + """
            GROUP BY command
            ORDER BY (SUM(raw_tokens) - SUM(out_tokens)) DESC
            LIMIT ?
            """;
        List<TopCommand> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            int idx = 1;
            ps.setLong(idx++, sinceEpoch);
            if (projectHash != null) ps.setString(idx++, projectHash);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TopCommand(
                        rs.getString("command"),
                        rs.getLong("uses"),
                        rs.getLong("sum_raw"),
                        rs.getLong("sum_out")
                    ));
                }
            }
        } catch (SQLException e) {
            this.degraded = true;
            log.warnf(e, "queryTopCommands failed: %s", e.getMessage());
        }
        return result;
    }

    public List<RecentCommand> queryRecent(int limit, String projectHash) {
        String projectFilter = projectHash != null ? " AND project = ?" : "";
        String sql = """
            SELECT ts, command, raw_tokens, out_tokens, exec_ms
            FROM commands
            WHERE 1=1
            """ + projectFilter + """
            ORDER BY ts DESC
            LIMIT ?
            """;
        List<RecentCommand> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            int idx = 1;
            if (projectHash != null) ps.setString(idx++, projectHash);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new RecentCommand(
                        rs.getLong("ts"),
                        rs.getString("command"),
                        rs.getInt("raw_tokens"),
                        rs.getInt("out_tokens"),
                        rs.getLong("exec_ms")
                    ));
                }
            }
        } catch (SQLException e) {
            this.degraded = true;
            log.warnf(e, "queryRecent failed: %s", e.getMessage());
        }
        return result;
    }



    public record AggregateStats(
        long totalCommands, long sumRaw, long sumOut, long sumExecMs) {
        public long tokensSaved() { return sumRaw - sumOut; }
        public int savingsPct() {
            return sumRaw == 0 ? 0 : (int)(100L * (sumRaw - sumOut) / sumRaw);
        }
        public long avgExecMs() {
            return totalCommands == 0 ? 0 : sumExecMs / totalCommands;
        }
    }

    public record DailyStat(String day, long count, long sumRaw, long sumOut) {
        public long saved() { return sumRaw - sumOut; }
    }

    public record WeeklyStat(String week, long count, long sumRaw, long sumOut) {
        public long saved() { return sumRaw - sumOut; }
    }

    public record TopCommand(String command, long uses, long sumRaw, long sumOut) {
        public long saved() { return sumRaw - sumOut; }
        public int savingsPct() {
            return sumRaw == 0 ? 0 : (int)(100L * (sumRaw - sumOut) / sumRaw);
        }
    }

    public record HookBaseline(String tool, String path, String sha256, long installedTs) {}

    public record RecentCommand(
        long ts, String command, int rawTokens, int outTokens, long execMs) {
        public int savingsPct() {
            return rawTokens == 0 ? 0 : (int)(100L * (rawTokens - outTokens) / rawTokens);
        }
    }



    private String buildAggregateQuery(long sinceEpoch, String projectHash) {
        return "SELECT COUNT(*) AS total_commands, " +
               "COALESCE(SUM(raw_tokens),0) AS sum_raw, " +
               "COALESCE(SUM(out_tokens),0) AS sum_out, " +
               "COALESCE(SUM(exec_ms),0) AS sum_exec_ms " +
               "FROM commands WHERE ts >= ?" +
               (projectHash != null ? " AND project = ?" : "");
    }

    private void bindParams(PreparedStatement ps, long sinceEpoch,
                            String projectHash) throws SQLException {
        ps.setLong(1, sinceEpoch);
        if (projectHash != null) ps.setString(2, projectHash);
    }

    private Long commandTsBound(String aggregate) {
        try (Statement st = connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT " + aggregate + " FROM commands")) {
            if (rs.next()) {
                long value = rs.getLong(1);
                return rs.wasNull() ? null : value;
            }
        } catch (SQLException e) {
            log.warnf(e, "Failed to read command timestamp bound: %s", e.getMessage());
        }
        return null;
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
                log.debugf("SQLite connection closed");
            } catch (SQLException e) {
                log.warnf("Failed to close SQLite connection: %s", e.getMessage());
            }
            connection = null;
        }
    }



    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            try {
                java.nio.file.Path dbFile = platformDirs.getDatabaseFile();
                String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
                java.sql.Driver driver = new org.sqlite.JDBC();
                connection = driver.connect(url, new java.util.Properties());
                if (connection == null) {
                    throw new SQLException("SQLite driver did not accept URL: " + url);
                }
                applyPragmas(connection);
                try {
                    SchemaMigrator.Result migrated = SchemaMigrator.migrate(connection);
                    this.schemaAhead = migrated.schemaAhead();
                    this.lastSchemaVersion = migrated.version();
                    this.migrateFailed = false;
                } catch (SQLException e) {
                    this.migrateFailed = true;
                    throw e;
                }
                prune(connection);
            } catch (SQLException e) {
                this.degraded = true;
                throw e;
            }
        }
        return connection;
    }

    private void applyPragmas(Connection connection) {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException e) {
            log.warnf("Could not set busy_timeout: %s", e.getMessage());
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode = WAL")) {
            if (rs.next()) {
                lastJournalMode = rs.getString(1);
            }
        } catch (SQLException e) {
            log.warnf("Could not enable WAL: %s", e.getMessage());
        }
    }

    private void prune(Connection connection) {
        long cutoff = RetentionPolicy.cutoffEpochSeconds();
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM commands WHERE ts < ?")) {
            ps.setLong(1, cutoff);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf("Command retention prune failed: %s", e.getMessage());
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM filter_outcomes WHERE ts < ?")) {
            ps.setLong(1, cutoff);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf("Outcome retention prune failed: %s", e.getMessage());
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM hook_events WHERE ts < ?")) {
            ps.setLong(1, cutoff);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf("Hook event retention prune failed: %s", e.getMessage());
        }
        try {
            lastTeeSweep = TeeRetention.prune(platformDirs.getDataDir());
        } catch (RuntimeException e) {
            log.warnf("Tee retention sweep failed: %s", e.getMessage());
        }
        try {
            BackupRetention.prune(platformDirs.getDataDir());
        } catch (RuntimeException e) {
            log.warnf("Hook backup retention sweep failed: %s", e.getMessage());
        }
    }
}
