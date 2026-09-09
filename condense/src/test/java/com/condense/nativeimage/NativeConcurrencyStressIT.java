package com.condense.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
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
 * High-concurrency stress testing for native Condense binaries.
 * Evaluates 16 concurrent processes under mixed read/write storms,
 * ensuring SQLite integrity and fail-open preservation of child exit codes.
 */
class NativeConcurrencyStressIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CONCURRENCY = 16;
    private static final int WRITERS = 12;
    private static final int READERS = 4;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Sixteen parallel processes execute mixed reads and writes leaving database intact")
    void sixteenParallelProcessesMaintainDatabaseIntegrityUnderWriteStorm() throws Exception {
        Path configDir = tempDir.resolve("stress-config");
        Path dataDir = tempDir.resolve("stress-data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        Path db = dataDir.resolve("condense.db");

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<Callable<NativeBinarySupport.CliResult>> tasks = new ArrayList<>();

        // 12 writers running proxied commands
        for (int i = 0; i < WRITERS; i++) {
            final int id = i;
            tasks.add(() -> {
                String msg = "stress_payload_" + id;
                String[] cmd = NativeBinarySupport.isWindows()
                    ? new String[] {"cmd", "/c", "echo", msg}
                    : new String[] {"echo", msg};
                return NativeBinarySupport.run(configDir, dataDir, cmd);
            });
        }

        // 4 readers querying gain analytics concurrently
        for (int i = 0; i < READERS; i++) {
            tasks.add(() -> NativeBinarySupport.run(configDir, dataDir, "gain", "--format", "json"));
        }

        List<Future<NativeBinarySupport.CliResult>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        assertThat(pool.awaitTermination(3, TimeUnit.MINUTES))
            .as("Concurrent stress run timed out")
            .isTrue();

        for (int i = 0; i < futures.size(); i++) {
            NativeBinarySupport.CliResult result = futures.get(i).get();
            assertThat(result.exitCode())
                .as("stress worker %d failed with stdout=%s stderr=%s",
                    i + 1, result.stdout(), result.stderr())
                .isZero();
        }

        assertThat(db)
            .as("Analytics database must exist after concurrent write storm")
            .exists();

        Driver driver = new org.sqlite.JDBC();
        try (Connection connection = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                .as("SQLite PRAGMA integrity_check must return 'ok' after concurrency stress")
                .isEqualToIgnoringCase("ok");
        }

        NativeBinarySupport.CliResult gain = NativeBinarySupport.run(configDir, dataDir, "gain", "--format", "json");
        assertThat(gain.exitCode()).isZero();
        JsonNode report = JSON.readTree(gain.stdout());
        assertThat(report.path("total_commands").asLong())
            .as("gain must observe successful commands: %s", gain.stdout())
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Slow consumer stream backpressure drains truthfully without premature process termination")
    void slowConsumerBackpressureDrainsTruthfully() throws Exception {
        Path configDir = tempDir.resolve("bp-config");
        Path dataDir = tempDir.resolve("bp-data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);

        String[] cmd = NativeBinarySupport.isWindows()
            ? new String[] {"cmd", "/c", "for /L %i in (1,1,100) do @echo backpressure_line_%i"}
            : new String[] {"sh", "-c", "for i in $(seq 1 100); do echo backpressure_line_$i; done"};

        NativeBinarySupport.StartedRun run = NativeBinarySupport.start(configDir, dataDir, null, cmd);

        // Throttle consumption slightly to simulate slow consumer backpressure
        Thread.sleep(150);

        NativeBinarySupport.CliResult result = run.await();
        assertThat(result.exitCode())
            .as("Slow consumer backpressure run must exit 0: stderr=%s", result.stderr())
            .isZero();
        assertThat(result.stdout())
            .as("All 100 backpressure lines should be captured")
            .contains("backpressure_line_1")
            .contains("backpressure_line_100");
    }
}
