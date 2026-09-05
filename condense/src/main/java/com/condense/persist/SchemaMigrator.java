package com.condense.persist;

import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Forward-only SQLite schema migrator keyed by {@code PRAGMA user_version}.
 *
 * <p>Version 1 creates the historical {@code commands} table (if missing) and
 * the {@code filter_outcomes} sibling. It does not {@code ALTER} {@code commands}.
 * Version 2 adds {@code hook_events} and {@code hook_baselines}.
 * A database written by a newer binary ({@code user_version > TARGET}) is left
 * untouched so an older CLI fail-opens instead of destroying data.
 */
public final class SchemaMigrator {

    public static final int TARGET_VERSION = 2;

    private static final Logger log = Logger.getLogger(SchemaMigrator.class);

    public record Result(int version, boolean schemaAhead, boolean migrated) {}

    private SchemaMigrator() {}

    public static int readUserVersion(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public static String readJournalMode(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    public static Result migrate(Connection connection) throws SQLException {
        int current = readUserVersion(connection);
        if (current > TARGET_VERSION) {
            log.warnf(
                "Analytics database schema version %d is newer than this binary (target %d); skipping migrations",
                current, TARGET_VERSION
            );
            return new Result(current, true, false);
        }
        if (current == TARGET_VERSION) {
            return new Result(current, false, false);
        }

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement st = connection.createStatement()) {
                if (current < 1) {
                    applyV1(st);
                }
                if (current < 2) {
                    applyV2(st);
                }
                st.executeUpdate("PRAGMA user_version = " + TARGET_VERSION);
            }
            connection.commit();
            return new Result(TARGET_VERSION, false, true);
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static void applyV1(Statement st) throws SQLException {
        st.executeUpdate("""
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
            """);
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_commands_ts ON commands(ts)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_commands_project ON commands(project)");
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS filter_outcomes (
                id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                ts                  INTEGER NOT NULL,
                command             TEXT    NOT NULL,
                project             TEXT,
                filter_name         TEXT,
                kind                TEXT    NOT NULL,
                stage_name          TEXT,
                fallback_succeeded  INTEGER NOT NULL,
                detail              TEXT
            )
            """);
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_outcomes_ts ON filter_outcomes(ts)");
    }

    private static void applyV2(Statement st) throws SQLException {
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS hook_events (
                id      INTEGER PRIMARY KEY AUTOINCREMENT,
                ts      INTEGER NOT NULL,
                tool    TEXT    NOT NULL,
                action  TEXT    NOT NULL,
                path    TEXT,
                sha256  TEXT,
                success INTEGER NOT NULL,
                detail  TEXT
            )
            """);
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_hook_events_ts ON hook_events(ts)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_hook_events_tool ON hook_events(tool)");
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS hook_baselines (
                tool         TEXT PRIMARY KEY,
                path         TEXT    NOT NULL,
                sha256       TEXT    NOT NULL,
                installed_ts INTEGER NOT NULL
            )
            """);
    }
}
