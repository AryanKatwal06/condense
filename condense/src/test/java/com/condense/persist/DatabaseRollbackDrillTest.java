package com.condense.persist;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.TrackingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseRollbackDrillTest {

    private final Driver driver = new org.sqlite.JDBC();

    @Test
    @DisplayName("Disaster recovery rollback drill: schema-ahead database preserves all records and passes integrity check")
    void disasterRecoveryRollbackDrillPreservesAllData(@TempDir Path tempDir) throws Exception {
        Path data = tempDir.resolve("rollback-data");
        Path config = tempDir.resolve("rollback-config");
        Files.createDirectories(data);
        Path db = data.resolve("condense.db");
        long now = CondenseClock.epochSeconds();

        // 1. Initialize v3 database and seed data across all tables
        try (Connection conn = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties())) {
            SchemaMigrator.Result initial = SchemaMigrator.migrate(conn);
            assertThat(initial.version()).isEqualTo(SchemaMigrator.TARGET_VERSION);
            assertThat(initial.migrated()).isTrue();

            try (Statement st = conn.createStatement()) {
                st.executeUpdate(String.format(
                    "INSERT INTO commands (ts, command, project, cwd, raw_tokens, out_tokens, exec_ms, estimator, schema_version) " +
                    "VALUES (%d, 'cargo test', 'demo', '/app', 500, 100, 250, 'utf8_weighted_v1', 1)", now));
                st.executeUpdate(String.format(
                    "INSERT INTO filter_outcomes (ts, command, project, kind, fallback_succeeded) " +
                    "VALUES (%d, 'cargo test', 'demo', 'PASSTHROUGH', 0)", now));
                st.executeUpdate(String.format(
                    "INSERT INTO hook_events (ts, tool, action, success) " +
                    "VALUES (%d, 'cursor', 'install', 1)", now));
                st.executeUpdate(String.format(
                    "INSERT INTO hook_baselines (tool, path, sha256, installed_ts) " +
                    "VALUES ('cursor', '/hooks/cursor.json', 'abc123hash', %d)", now));

                // Verify SQLite PRAGMA integrity_check
                try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualToIgnoringCase("ok");
                }
            }
        }

        // 2. Simulate future release migration: user_version bumped to 4 with a new table
        try (Connection conn = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = conn.createStatement()) {
            st.executeUpdate("PRAGMA user_version = 4");
            st.executeUpdate("CREATE TABLE v4_future_features (id INTEGER PRIMARY KEY, feature_name TEXT)");
            st.executeUpdate("INSERT INTO v4_future_features VALUES (1, 'distributed_telemetry')");
        }

        // 3. Rollback drill: Older binary (TARGET_VERSION = 3) encounters the database
        try (Connection conn = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties())) {
            SchemaMigrator.Result rollbackResult = SchemaMigrator.migrate(conn);

            // Forward-compatibility contract: schemaAhead must be true, migrated must be false
            assertThat(rollbackResult.schemaAhead()).isTrue();
            assertThat(rollbackResult.migrated()).isFalse();
            assertThat(rollbackResult.version()).isEqualTo(4);

            // Zero data loss: All original records and future records must survive intact
            try (Statement st = conn.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM commands")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM filter_outcomes")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM hook_events")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM hook_baselines")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM v4_future_features")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }

                // Final integrity check passes
                try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualToIgnoringCase("ok");
                }
            }
        }

        // 4. Verify TrackingRepository also operates safely in schema-ahead mode
        TrackingRepository repo = new TrackingRepository(new IsolatedPlatformDirs(config, data));
        try {
            assertThat(repo.schemaVersion()).isEqualTo(4);
            assertThat(repo.isSchemaAhead()).isTrue();
            assertThat(repo.countAll()).isEqualTo(1);
        } finally {
            repo.close();
        }
    }

    @Test
    @DisplayName("WAL checkpoint and atomic recovery drill")
    void walCheckpointAndReopenDrill(@TempDir Path tempDir) throws Exception {
        Path data = tempDir.resolve("wal-data");
        Path config = tempDir.resolve("wal-config");
        Files.createDirectories(data);
        Path db = data.resolve("condense.db");
        long now = CondenseClock.epochSeconds();

        try (Connection conn = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties())) {
            SchemaMigrator.migrate(conn);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("PRAGMA journal_mode = WAL");
                for (int i = 0; i < 10; i++) {
                    st.executeUpdate(String.format(
                        "INSERT INTO commands (ts, command, project, cwd, raw_tokens, out_tokens, exec_ms) " +
                        "VALUES (%d, 'cmd-%d', 'proj', '/dir', 100, 20, 10)",
                        now - i, i
                    ));
                }
                // Run WAL checkpoint
                try (ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(0); // 0 = SQLITE_OK
                }
            }
        }

        // Re-open and verify
        TrackingRepository repo = new TrackingRepository(new IsolatedPlatformDirs(config, data));
        try {
            assertThat(repo.schemaVersion()).isEqualTo(SchemaMigrator.TARGET_VERSION);
            assertThat(repo.countAll()).isEqualTo(10);
            assertThat(repo.journalMode()).isEqualToIgnoringCase("wal");
        } finally {
            repo.close();
        }
    }

    @Test
    @DisplayName("Legacy query contract: older projections succeed on upgraded v3 schema")
    void downgradedLegacyReaderQueryContract(@TempDir Path tempDir) throws Exception {
        Path data = tempDir.resolve("legacy-data");
        Files.createDirectories(data);
        Path db = data.resolve("condense.db");
        long now = CondenseClock.epochSeconds();

        try (Connection conn = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties())) {
            SchemaMigrator.migrate(conn);
            try (Statement st = conn.createStatement()) {
                // Insert using v1 syntax omitting v3 columns
                st.executeUpdate(String.format(
                    "INSERT INTO commands (ts, command, project, cwd, raw_tokens, out_tokens, exec_ms) " +
                    "VALUES (%d, 'mvn test', 'my-app', '/ws', 400, 80, 120)", now));

                // Legacy query projection
                try (ResultSet rs = st.executeQuery(
                        "SELECT id, ts, command, project, cwd, raw_tokens, out_tokens, exec_ms FROM commands")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("command")).isEqualTo("mvn test");
                    assertThat(rs.getInt("raw_tokens")).isEqualTo(400);
                }

                // Verify default values on v3 columns
                try (ResultSet rs = st.executeQuery(
                        "SELECT estimator, schema_version FROM commands WHERE command = 'mvn test'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("estimator")).isEqualTo("utf8_weighted_v1");
                    assertThat(rs.getInt("schema_version")).isEqualTo(1);
                }
            }
        }
    }

    @Test
    @DisplayName("Database disaster recovery drill: handles corruption by isolating corrupt file and recreating clean DB")
    void databaseRebuildDrillRecoversGracefullyFromCorruptFile(@TempDir Path tempDir) throws Exception {
        Path data = tempDir.resolve("corrupt-data");
        Path config = tempDir.resolve("corrupt-config");
        Files.createDirectories(data);
        Path db = data.resolve("condense.db");

        // Write corrupt garbage to DB
        Files.writeString(db, "CORRUPT_HEADER_NOT_A_VALID_SQLITE_DATABASE");

        // Follow Disaster Recovery runbook: Move corrupt database to .corrupt backup
        Path corruptBackup = data.resolve("condense.db.corrupt");
        Files.move(db, corruptBackup);
        assertThat(Files.exists(corruptBackup)).isTrue();
        assertThat(Files.exists(db)).isFalse();

        // Fresh repo initialization recreates and migrates
        TrackingRepository repo = new TrackingRepository(new IsolatedPlatformDirs(config, data));
        try {
            repo.insert("git diff", "proj", "/dir", 80, 15, 2L);
            assertThat(repo.schemaVersion()).isEqualTo(SchemaMigrator.TARGET_VERSION);
            assertThat(repo.countAll()).isEqualTo(1);
            assertThat(repo.isDegraded()).isFalse();
        } finally {
            repo.close();
        }
    }
}
