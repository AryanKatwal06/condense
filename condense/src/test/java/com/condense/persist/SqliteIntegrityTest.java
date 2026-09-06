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

class SqliteIntegrityTest {

    @TempDir
    Path tempDir;

    @Test
    void corruptDbSkipsMigrateAndMarksDegraded() throws Exception {
        Path data = tempDir.resolve("data");
        IsolatedPlatformDirs dirs = new IsolatedPlatformDirs(tempDir.resolve("config"), data);
        TrackingRepository seed = new TrackingRepository(dirs);
        try {
            seed.insert("keep", "proj", "/tmp", 4, 1, 1L);
        } finally {
            seed.close();
        }

        Path db = data.resolve("condense.db");
        Files.deleteIfExists(data.resolve("condense.db-wal"));
        Files.deleteIfExists(data.resolve("condense.db-shm"));
        Files.writeString(db, "not a sqlite database");

        TrackingRepository repo = new TrackingRepository(dirs);
        try {
            assertThatCode(() -> repo.insert("x", "y", "/tmp", 1, 1, 1L)).doesNotThrowAnyException();
            assertThat(repo.isDegraded() || repo.isIntegrityFailed()).isTrue();
        } finally {
            repo.close();
        }
    }

    @Test
    void healthyOpenReportsIntegrityOk() throws Exception {
        Path data = tempDir.resolve("ok");
        IsolatedPlatformDirs dirs = new IsolatedPlatformDirs(tempDir.resolve("cfg"), data);
        TrackingRepository repo = new TrackingRepository(dirs);
        try {
            repo.insert("ok", "proj", "/tmp", 1, 1, 1L);
            assertThat(repo.isIntegrityFailed()).isFalse();
        } finally {
            repo.close();
        }
        Path db = data.resolve("condense.db");
        Driver driver = new org.sqlite.JDBC();
        try (Connection connection = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("ok");
        }
    }
}
