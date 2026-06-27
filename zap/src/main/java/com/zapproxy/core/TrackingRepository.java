package com.zapproxy.core;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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

    @PreDestroy
    void close() {
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
            String url = "jdbc:sqlite:" + platformDirs.getDatabaseFile().toAbsolutePath();
            connection = DriverManager.getConnection(url);
            initSchema();
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
