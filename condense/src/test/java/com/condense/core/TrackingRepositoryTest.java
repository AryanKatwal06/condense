package com.condense.core;

import com.condense.filter.pipeline.FilterIncident;
import com.condense.persist.RetentionPolicy;
import com.condense.persist.SchemaMigrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TrackingRepositoryTest {

    @TempDir
    Path tempDir;

    private TrackingRepository repo;

    @BeforeEach
    void setUp() {
        repo = new TrackingRepository(new IsolatedPlatformDirs(
            tempDir.resolve("config"),
            tempDir.resolve("data")
        ));
    }

    @AfterEach
    void tearDown() {
        repo.close();
    }

    @Test
    void schemaIsCreatedOnFirstAccess() throws Exception {
        assertThat(repo.countAll()).isZero();
        Path db = tempDir.resolve("data").resolve("condense.db");
        assertThat(db).exists();
        Driver driver = new org.sqlite.JDBC();
        try (Connection connection = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = connection.createStatement();
             ResultSet version = st.executeQuery("PRAGMA user_version")) {
            assertThat(version.next()).isTrue();
            assertThat(version.getInt(1)).isEqualTo(SchemaMigrator.TARGET_VERSION);
        }
        assertThat(repo.journalMode()).isEqualToIgnoringCase("wal");
    }

    @Test
    void insertIncreasesCount() {
        long before = repo.countAll();
        repo.insert("git status", "abc123def456", "/tmp/project", 500, 100, 42L);
        assertThat(repo.countAll()).isEqualTo(before + 1);
    }

    @Test
    void insertWithNullProjectDoesNotThrow() {
        long before = repo.countAll();
        repo.insert("ls -la", null, "/tmp", 200, 50, 5L);
        assertThat(repo.countAll()).isEqualTo(before + 1);
    }

    @Test
    void insertNeverThrowsEvenWithInvalidData() {
        assertThatCode(() -> repo.insert("", null, null, -1, -1, -1L))
            .doesNotThrowAnyException();
    }

    @Test
    void onlyBusyAndLockedAreRetried() {
        assertThat(TrackingRepository.isBusyOrLocked(new SQLException("busy", "x", 5))).isTrue();
        assertThat(TrackingRepository.isBusyOrLocked(new SQLException("locked", "x", 6))).isTrue();
        assertThat(TrackingRepository.isBusyOrLocked(new SQLException("[SQLITE_READONLY] write", "x", 8)))
            .isFalse();
        assertThat(TrackingRepository.isBusyOrLocked(new SQLException("[SQLITE_BUSY] timeout")))
            .isTrue();
        assertThat(TrackingRepository.isBusyOrLocked(new SQLException("[SQLITE_READONLY] SQLITE_BUSY lookalike")))
            .isFalse();
    }

    @Test
    void insertOutcomeIsFailOpenAndQueryable() {
        repo.insert("pytest", "abc123def456", "/tmp", 10, 4, 3L);
        assertThatCode(() -> repo.insertOutcome(
            "pytest",
            "abc123def456",
            FilterIncident.stageException("ThrowingStage", "boom")
        )).doesNotThrowAnyException();
        assertThat(repo.countOutcomes()).isEqualTo(1);
        assertThat(repo.outcomeCountsByKind())
            .containsEntry(FilterIncident.KIND_STAGE_EXCEPTION, 1L);
        assertThat(repo.isDegraded()).isFalse();
    }

    @Test
    void pruneDeletesExpiredRowsAndKeepsRecent() {
        long now = System.currentTimeMillis() / 1000L;
        long expired = RetentionPolicy.cutoffEpochSeconds(now) - 60;
        repo.insertAt(expired, "old cmd", "proj", "/tmp", 10, 2, 1L);
        repo.insertAt(now, "new cmd", "proj", "/tmp", 10, 2, 1L);
        repo.close();

        TrackingRepository reopened = new TrackingRepository(new IsolatedPlatformDirs(
            tempDir.resolve("config"),
            tempDir.resolve("data")
        ));
        try {
            assertThat(reopened.countAll()).isEqualTo(1);
            assertThat(reopened.queryRecent(5, null).get(0).command()).isEqualTo("new cmd");
        } finally {
            reopened.close();
        }
    }
}
