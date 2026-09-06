package com.condense.nativeimage;

import com.condense.persist.SchemaMigrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native-image chaos proof. Never skips.
 */
class NativeChaosIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void corruptDbProxyStillExitsZero() throws Exception {
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);

        NativeBinarySupport.CliResult first = NativeBinarySupport.run(
            configDir, dataDir, NativeBinarySupport.trivialSucceedingCommand());
        assertThat(first.exitCode())
            .as("seed run stdout=%s stderr=%s", first.stdout(), first.stderr())
            .isZero();

        corruptDatabase(dataDir);

        NativeBinarySupport.CliResult second = NativeBinarySupport.run(
            configDir, dataDir, NativeBinarySupport.trivialSucceedingCommand());
        assertThat(second.exitCode())
            .as("fail-open run must keep the child exit code: stdout=%s stderr=%s",
                second.stdout(), second.stderr())
            .isZero();
    }

    @Test
    void schemaAheadDoctorWarns() throws Exception {
        Path configDir = tempDir.resolve("ahead-config");
        Path dataDir = tempDir.resolve("ahead-data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);

        NativeBinarySupport.CliResult seed = NativeBinarySupport.run(
            configDir, dataDir, NativeBinarySupport.trivialSucceedingCommand());
        assertThat(seed.exitCode()).isZero();

        Path db = dataDir.resolve("condense.db");
        Driver driver = new org.sqlite.JDBC();
        try (Connection connection = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = connection.createStatement()) {
            st.executeUpdate("PRAGMA user_version = 99");
        }

        NativeBinarySupport.CliResult doctor = NativeBinarySupport.run(
            configDir, dataDir, "doctor", "--format", "json");
        assertThat(doctor.exitCode())
            .as("doctor stdout=%s stderr=%s", doctor.stdout(), doctor.stderr())
            .isZero();
        JsonNode diagnosis = JSON.readTree(doctor.stdout());
        assertThat(diagnosis.get("schema_ahead").asBoolean()).isTrue();
        assertThat(diagnosis.get("schema_version").asInt()).isGreaterThan(SchemaMigrator.TARGET_VERSION);
        assertThat(doctor.stdout()).contains("schema_ahead");
    }

    @Test
    void leftoverTrustTmpStillReadsLastGood() throws Exception {
        Path configDir = tempDir.resolve("trust-config");
        Path dataDir = tempDir.resolve("trust-data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        Path trust = configDir.resolve("trust.json");
        String lastGood = """
            {
              "schema_version": 1,
              "entries": [
                {
                  "path": "/tmp/example/filters.toml",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "capabilities": ["reduce"],
                  "trusted_at": "2020-01-01T00:00:00Z"
                }
              ]
            }
            """;
        Files.writeString(trust, lastGood);
        Files.writeString(configDir.resolve("trust.json.tmp"), "{PARTIAL");

        NativeBinarySupport.CliResult first = NativeBinarySupport.run(
            configDir, dataDir, "doctor", "--format", "json");
        assertThat(first.exitCode())
            .as("first doctor stdout=%s stderr=%s", first.stdout(), first.stderr())
            .isZero();
        JsonNode one = JSON.readTree(first.stdout());
        assertThat(one.get("trust_readable").asBoolean()).isTrue();
        assertThat(one.get("trust_entries").asInt()).isEqualTo(1);

        NativeBinarySupport.CliResult second = NativeBinarySupport.run(
            configDir, dataDir, "doctor", "--format", "json");
        assertThat(second.exitCode()).isZero();
        JsonNode two = JSON.readTree(second.stdout());
        assertThat(two.get("trust_readable").asBoolean()).isTrue();
        assertThat(two.get("trust_entries").asInt()).isEqualTo(1);
        assertThat(Files.readString(trust).replace("\r\n", "\n").trim())
            .isEqualTo(lastGood.replace("\r\n", "\n").trim());
    }

    private static void corruptDatabase(Path dataDir) throws Exception {
        try (Stream<Path> stream = Files.list(dataDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith("condense.db")) {
                    deleteRecursively(path);
                }
            }
        }
        Files.writeString(dataDir.resolve("condense.db"), "not a sqlite database");
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.walk(path)) {
                for (Path child : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(child);
                }
            }
        } else {
            Files.deleteIfExists(path);
        }
    }
}
