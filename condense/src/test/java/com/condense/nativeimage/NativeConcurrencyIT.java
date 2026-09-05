package com.condense.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Five parallel native invocations against one isolated database. Integrity is
 * checked through the Xerial driver instance (not the sqlite3 CLI). Gain must
 * see at least one row; Windows concurrent writers are not required to land all
 * five. Never skips.
 */
class NativeConcurrencyIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PARALLEL = 5;

    @TempDir
    Path tempDir;

    @Test
    void fiveParallelInvocationsLeaveAnIntactDatabase() throws Exception {
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        Path db = dataDir.resolve("condense.db");

        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        List<Callable<NativeBinarySupport.CliResult>> tasks = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) {
            tasks.add(() -> NativeBinarySupport.run(
                configDir, dataDir, NativeBinarySupport.trivialSucceedingCommand()));
        }
        List<Future<NativeBinarySupport.CliResult>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES))
            .as("parallel native invocations timed out")
            .isTrue();

        for (int i = 0; i < futures.size(); i++) {
            NativeBinarySupport.CliResult result = futures.get(i).get();
            assertThat(result.exitCode())
                .as("parallel run %d stdout=%s stderr=%s", i + 1, result.stdout(), result.stderr())
                .isZero();
        }

        assertThat(db)
            .as("analytics database should exist after concurrent writes")
            .exists();

        Driver driver = new org.sqlite.JDBC();
        try (Connection connection = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                .as("SQLite integrity_check")
                .isEqualToIgnoringCase("ok");
        }

        NativeBinarySupport.CliResult gain = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--format", "json");
        assertThat(gain.exitCode())
            .as("gain stdout=%s stderr=%s", gain.stdout(), gain.stderr())
            .isZero();
        JsonNode report = JSON.readTree(gain.stdout());
        assertThat(report.get("total_commands").asLong())
            .as("gain must see at least one concurrent write: %s", gain.stdout())
            .isGreaterThanOrEqualTo(1);
    }
}
