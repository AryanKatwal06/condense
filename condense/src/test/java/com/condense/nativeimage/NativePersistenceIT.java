package com.condense.nativeimage;

import com.condense.persist.LegacyDatabase;
import com.condense.persist.SchemaMigrator;
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
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native-image proof that a v0 analytics database is migrated inside the
 * shipped binary, and that {@code condense doctor} can explain empty gain.
 */
class NativePersistenceIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void nativeBinaryMigratesV0DatabaseAndDoctorReportsHealth() throws Exception {
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        Path db = dataDir.resolve("condense.db");
        LegacyDatabase.writeV0(db);

        NativeBinarySupport.CliResult gain = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--format", "json"
        );
        assertThat(gain.exitCode()).isZero();
        assertThat(gain.stderr()).doesNotContain("analytics unavailable");
        assertThat(gain.stderr()).doesNotContain("No suitable driver found");
        JsonNode report = JSON.readTree(gain.stdout());
        assertThat(report.get("total_commands").asLong())
            .as("gain must see the migrated v0 seed row: %s", gain.stdout())
            .isGreaterThanOrEqualTo(1);

        Driver driver = new org.sqlite.JDBC();
        try (Connection connection = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = connection.createStatement()) {
            try (ResultSet version = st.executeQuery("PRAGMA user_version")) {
                assertThat(version.next()).isTrue();
                assertThat(version.getInt(1)).isEqualTo(SchemaMigrator.TARGET_VERSION);
            }
            try (ResultSet journal = st.executeQuery("PRAGMA journal_mode")) {
                assertThat(journal.next()).isTrue();
                assertThat(journal.getString(1)).isEqualToIgnoringCase("wal");
            }
            try (ResultSet tables = st.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='filter_outcomes'")) {
                assertThat(tables.next()).isTrue();
            }
            try (ResultSet seed = st.executeQuery(
                    "SELECT command FROM commands WHERE command='" + LegacyDatabase.SEED_COMMAND + "'")) {
                assertThat(seed.next()).isTrue();
            }
        }

        NativeBinarySupport.CliResult doctor = NativeBinarySupport.run(
            configDir, dataDir, "doctor", "--format", "json"
        );
        assertThat(doctor.exitCode())
            .as("doctor stdout=%s stderr=%s", doctor.stdout(), doctor.stderr())
            .isZero();
        JsonNode diagnosis = JSON.readTree(doctor.stdout());
        assertThat(diagnosis.get("schema_version").asInt()).isEqualTo(SchemaMigrator.TARGET_VERSION);
        assertThat(diagnosis.get("empty_tracking_reason").isNull()).isTrue();
    }

    @Test
    void nativeBinaryMigratesV1DatabaseToVersionTwo() throws Exception {
        Path configDir = tempDir.resolve("v1-config");
        Path dataDir = tempDir.resolve("v1-data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        Path db = dataDir.resolve("condense.db");
        LegacyDatabase.writeV1(db);

        NativeBinarySupport.CliResult gain = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--format", "json"
        );
        assertThat(gain.exitCode()).isZero();

        Driver driver = new org.sqlite.JDBC();
        try (Connection connection = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = connection.createStatement()) {
            try (ResultSet version = st.executeQuery("PRAGMA user_version")) {
                assertThat(version.next()).isTrue();
                assertThat(version.getInt(1)).isEqualTo(SchemaMigrator.TARGET_VERSION);
            }
            try (ResultSet tables = st.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='hook_events'")) {
                assertThat(tables.next()).isTrue();
            }
        }
    }

    @Test
    void doctorOnEmptyDirsExplainsMissingTracking() throws Exception {
        Path configDir = tempDir.resolve("empty-config");
        Path dataDir = tempDir.resolve("empty-data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);

        NativeBinarySupport.CliResult doctor = NativeBinarySupport.run(
            configDir, dataDir, "doctor", "--format", "json"
        );
        assertThat(doctor.exitCode())
            .as("doctor stdout=%s stderr=%s", doctor.stdout(), doctor.stderr())
            .isZero();
        JsonNode diagnosis = JSON.readTree(doctor.stdout());
        assertThat(diagnosis.get("empty_tracking_reason").asText())
            .isIn("no_database", "hooks_absent", "zero_rows");
        assertThat(diagnosis.get("next_step").asText()).isNotBlank();
    }
}
