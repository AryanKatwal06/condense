package com.condense.persist;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.TrackingRepository;
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
import static org.assertj.core.api.Assertions.assertThatCode;

class SchemaMigratorTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesV0PreservingRowsAndIsIdempotent() throws Exception {
        Path data = tempDir.resolve("data");
        Path db = data.resolve("condense.db");
        LegacyDatabase.writeV0(db);

        TrackingRepository repo = new TrackingRepository(new IsolatedPlatformDirs(tempDir.resolve("config"), data));
        try {
            assertThat(repo.countAll()).isEqualTo(1);
            assertThat(repo.queryRecent(1, null).get(0).command()).isEqualTo(LegacyDatabase.SEED_COMMAND);
            assertThat(repo.schemaVersion()).isEqualTo(SchemaMigrator.TARGET_VERSION);
            assertThat(repo.journalMode()).isEqualToIgnoringCase("wal");
            assertThat(tableExists(db, "filter_outcomes")).isTrue();
            assertThat(repo.isSchemaAhead()).isFalse();
        } finally {
            repo.close();
        }

        TrackingRepository second = new TrackingRepository(new IsolatedPlatformDirs(tempDir.resolve("config"), data));
        try {
            assertThat(second.countAll()).isEqualTo(1);
            assertThat(second.schemaVersion()).isEqualTo(SchemaMigrator.TARGET_VERSION);
            assertThat(second.queryRecent(1, null).get(0).command()).isEqualTo(LegacyDatabase.SEED_COMMAND);
        } finally {
            second.close();
        }
    }

    @Test
    void freshFileReachesVersionOne() throws Exception {
        Path data = tempDir.resolve("fresh");
        TrackingRepository repo = new TrackingRepository(new IsolatedPlatformDirs(tempDir.resolve("config"), data));
        try {
            repo.insert("git status", "abc", "/tmp", 4, 1, 1L);
            assertThat(repo.schemaVersion()).isEqualTo(SchemaMigrator.TARGET_VERSION);
            assertThat(tableExists(data.resolve("condense.db"), "filter_outcomes")).isTrue();
        } finally {
            repo.close();
        }
    }

    @Test
    void newerSchemaIsLeftUntouched() throws Exception {
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
            st.executeUpdate("CREATE TABLE extra_future (id INTEGER)");
        }

        TrackingRepository repo = new TrackingRepository(new IsolatedPlatformDirs(tempDir.resolve("config"), data));
        try {
            assertThat(repo.schemaVersion()).isEqualTo(99);
            assertThat(repo.isSchemaAhead()).isTrue();
            assertThat(repo.countAll()).isEqualTo(1);
            assertThat(tableExists(db, "extra_future")).isTrue();
        } finally {
            repo.close();
        }
    }

    @Test
    void corruptFileDoesNotEscapeRepositoryApis() throws Exception {
        Path data = tempDir.resolve("corrupt");
        Files.createDirectories(data);
        Files.writeString(data.resolve("condense.db"), "not a sqlite database");

        TrackingRepository repo = new TrackingRepository(new IsolatedPlatformDirs(tempDir.resolve("config"), data));
        try {
            assertThatCode(() -> repo.insert("x", "y", "/tmp", 1, 1, 1L)).doesNotThrowAnyException();
            assertThat(repo.isDegraded()).isTrue();
            assertThat(repo.countAll()).isZero();
        } finally {
            repo.close();
        }
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
