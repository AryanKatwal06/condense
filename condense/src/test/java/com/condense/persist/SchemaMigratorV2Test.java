package com.condense.persist;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.TrackingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SchemaMigratorV2Test {

    @TempDir
    Path tempDir;

    @Test
    void freshDatabaseReachesVersionTwoWithHookTables() throws Exception {
        Path data = tempDir.resolve("fresh");
        TrackingRepository repo = new TrackingRepository(new IsolatedPlatformDirs(tempDir.resolve("config"), data));
        try {
            repo.insert("git status", "abc", "/tmp", 4, 1, 1L);
            assertThat(repo.schemaVersion()).isEqualTo(2);
            assertThat(tableExists(data.resolve("condense.db"), "hook_events")).isTrue();
            assertThat(tableExists(data.resolve("condense.db"), "hook_baselines")).isTrue();
            repo.insertHookEvent("CURSOR", "install", "/tmp/hook.sh", "abc", true, null);
            repo.upsertHookBaseline("CURSOR", "/tmp/hook.sh", "abc");
            assertThat(repo.countHookEvents()).isEqualTo(1);
            assertThat(repo.findHookBaseline("CURSOR").sha256()).isEqualTo("abc");
        } finally {
            repo.close();
        }
    }

    @Test
    void seededV1MigratesToV2WithoutLosingRows() throws Exception {
        Path data = tempDir.resolve("v1");
        Path db = data.resolve("condense.db");
        LegacyDatabase.writeV1(db);

        TrackingRepository repo = new TrackingRepository(new IsolatedPlatformDirs(tempDir.resolve("config"), data));
        try {
            assertThat(repo.countAll()).isEqualTo(1);
            assertThat(repo.schemaVersion()).isEqualTo(2);
            assertThat(tableExists(db, "hook_events")).isTrue();
            assertThat(tableExists(db, "hook_baselines")).isTrue();
            assertThat(repo.queryRecent(1, null).get(0).command()).isEqualTo(LegacyDatabase.SEED_COMMAND);
        } finally {
            repo.close();
        }
    }

    @Test
    void newerThanTwoIsLeftUntouched() throws Exception {
        Path data = tempDir.resolve("ahead");
        TrackingRepository seed = new TrackingRepository(new IsolatedPlatformDirs(tempDir.resolve("config"), data));
        try {
            seed.insert("keep-me", "abc", "/tmp", 8, 2, 1L);
        } finally {
            seed.close();
        }
        Path db = data.resolve("condense.db");
        Driver driver = new org.sqlite.JDBC();
        try (Connection connection = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = connection.createStatement()) {
            st.executeUpdate("PRAGMA user_version = 99");
        }
        TrackingRepository repo = new TrackingRepository(new IsolatedPlatformDirs(tempDir.resolve("config"), data));
        try {
            assertThat(repo.schemaVersion()).isEqualTo(99);
            assertThat(repo.isSchemaAhead()).isTrue();
            assertThat(repo.countAll()).isEqualTo(1);
        } finally {
            repo.close();
        }
    }

    @Test
    void hookInsertIsFailOpenAfterClose() {
        TrackingRepository repo = new TrackingRepository(
            new IsolatedPlatformDirs(tempDir.resolve("cfg"), tempDir.resolve("data")));
        repo.close();
        assertThatCode(() -> repo.insertHookEvent("CURSOR", "install", null, null, true, null))
            .doesNotThrowAnyException();
        repo.close();
    }

    private static boolean tableExists(Path db, String table) throws Exception {
        Driver driver = new org.sqlite.JDBC();
        try (Connection connection = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return rs.next();
        }
    }
}
