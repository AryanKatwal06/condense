package com.zapproxy.core;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists command execution records to a local SQLite database for
 * analytics reporting via {@code zap gain}.
 *
 * <p>The database is created automatically on first use at the path
 * returned by {@link PlatformDirs#getDatabaseFile()}. Analytics failures
 * are non-fatal — they log a warning and never propagate to the caller.
 */
@ApplicationScoped
public class TrackingRepository {

    private static final Logger log = Logger.getLogger(TrackingRepository.class);

    private static final String CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS commands (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            ts         INTEGER NOT NULL,
            command    TEXT    NOT NULL,
            project    TEXT,
            cwd        TEXT,
            raw_tokens INTEGER NOT NULL,
            out_tokens INTEGER NOT NULL,
            exec_ms    INTEGER NOT NULL
        )
        """;

    private static final String CREATE_IDX_TS =
        "CREATE INDEX IF NOT EXISTS idx_commands_ts ON commands(ts)";

    private static final String CREATE_IDX_PROJECT =
        "CREATE INDEX IF NOT EXISTS idx_commands_project ON commands(project)";

    private static final String INSERT = """
        INSERT INTO commands(ts, command, project, cwd, raw_tokens, out_tokens, exec_ms)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    @Inject
    PlatformDirs platformDirs;

    private Connection connection;

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
        try {
            try (PreparedStatement ps = connection().prepareStatement(INSERT)) {
                ps.setLong(1, System.currentTimeMillis() / 1000L);
                ps.setString(2, command);
                ps.setString(3, project);
                ps.setString(4, cwd);
                ps.setInt(5, rawTokens);
                ps.setInt(6, outTokens);
                ps.setLong(7, execMs);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warnf("Failed to record analytics for '%s': %s", command, e.getMessage());
        }
    }

    /**
     * Returns the total number of recorded commands.
     * Used in tests and by {@code zap gain}.
     */
    public long countAll() {
        try (Statement st = connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM commands")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            log.warnf("Failed to count commands: %s", e.getMessage());
            return 0L;
        }
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
            log.warnf("queryAggregate failed: %s", e.getMessage());
        }
        return new AggregateStats(0, 0, 0, 0);
    }

    /**
     * Returns per-day token savings for the last {@code days} days.
     * Used by {@code zap gain --graph}.
     */
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
            log.warnf("queryDaily failed: %s", e.getMessage());
        }
        return result;
    }

    /**
     * Returns per-week token savings for the last {@code weeks} weeks.
     */
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
            log.warnf("queryWeekly failed: %s", e.getMessage());
        }
        return result;
    }

    /**
     * Returns the top N commands by total tokens saved.
     */
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
            log.warnf("queryTopCommands failed: %s", e.getMessage());
        }
        return result;
    }

    /**
     * Returns the last N command executions with their token data.
     */
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
            log.warnf("queryRecent failed: %s", e.getMessage());
        }
        return result;
    }

    // ── Helper records (inner) ────────────────────────────────────────────────────

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

    public record RecentCommand(
        long ts, String command, int rawTokens, int outTokens, long execMs) {
        public int savingsPct() {
            return rawTokens == 0 ? 0 : (int)(100L * (rawTokens - outTokens) / rawTokens);
        }
    }

    // ── SQL helpers ───────────────────────────────────────────────────────────────

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

    public void close() {
        if (connection != null) {
            try {
                connection.close();
                log.debugf("SQLite connection closed");
            } catch (SQLException e) {
                log.warnf("Failed to close SQLite connection: %s", e.getMessage());
            }
        }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            java.nio.file.Path dbFile = platformDirs.getDatabaseFile();
            boolean dbExists = java.nio.file.Files.exists(dbFile);
            String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
            connection = DriverManager.getConnection(url);
            if (!dbExists) {
                initSchema();
            }
        }
        return connection;
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(CREATE_TABLE);
            st.executeUpdate(CREATE_IDX_TS);
            st.executeUpdate(CREATE_IDX_PROJECT);
        }
    }
}
