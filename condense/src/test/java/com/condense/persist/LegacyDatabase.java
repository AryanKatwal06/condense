package com.condense.persist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Builds a pre-Phase-7 SQLite file at runtime. Never checked in ({@code *.db} is gitignored).
 */
public final class LegacyDatabase {

    public static final String SEED_COMMAND = "legacy-v0-seed";

    private LegacyDatabase() {}

    public static void writeV0(Path dbFile) throws SQLException, IOException {
        Files.createDirectories(dbFile.getParent());
        Driver driver = new org.sqlite.JDBC();
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        Connection connection = driver.connect(url, new Properties());
        if (connection == null) {
            throw new SQLException("SQLite driver did not accept URL: " + url);
        }
        try (connection; Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE commands (
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
            st.executeUpdate("CREATE INDEX idx_commands_ts ON commands(ts)");
            st.executeUpdate("CREATE INDEX idx_commands_project ON commands(project)");
            long seedTs = System.currentTimeMillis() / 1000L - 3600L;
            st.executeUpdate("""
                INSERT INTO commands(ts, command, project, cwd, raw_tokens, out_tokens, exec_ms)
                VALUES (""" + seedTs + ", '" + SEED_COMMAND + "', 'deadbeefcafe', '/tmp/legacy', 100, 40, 12)"
            );
        }
    }

    /** Phase-7 schema: commands + filter_outcomes, {@code user_version = 1}. */
    public static void writeV1(Path dbFile) throws SQLException, IOException {
        writeV0(dbFile);
        Driver driver = new org.sqlite.JDBC();
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        Connection connection = driver.connect(url, new Properties());
        if (connection == null) {
            throw new SQLException("SQLite driver did not accept URL: " + url);
        }
        try (connection; Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE filter_outcomes (
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
            st.executeUpdate("CREATE INDEX idx_outcomes_ts ON filter_outcomes(ts)");
            st.executeUpdate("PRAGMA user_version = 1");
        }
    }
}
