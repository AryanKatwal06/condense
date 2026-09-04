package com.condense.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.condense.core.Utf8WeightedTokenEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-002 regression: SQLite persistence inside the native image, rewritten
 * against isolated config/data dirs so it cannot mutate the developer's database.
 */
class NativeAnalyticsIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void nativeBinaryPersistsAnalyticsAndGainReportsThem() throws Exception {
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);

        NativeBinarySupport.CliResult proxied = NativeBinarySupport.run(
            configDir, dataDir, NativeBinarySupport.trivialSucceedingCommand()
        );
        assertThat(proxied.exitCode()).isZero();
        assertThat(proxied.stderr()).doesNotContain("No suitable driver found");

        NativeBinarySupport.CliResult gain = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--format", "json"
        );
        assertThat(gain.exitCode()).isZero();
        assertThat(gain.stderr()).doesNotContain("analytics unavailable");

        JsonNode report = JSON.readTree(gain.stdout());
        assertThat(report.get("total_commands").asLong())
            .as("gain JSON after a proxied command must report at least one row: %s", gain.stdout())
            .isGreaterThanOrEqualTo(1);
        JsonNode estimator = report.get("estimator");
        assertThat(estimator)
            .as("gain JSON must include estimator metadata: %s", gain.stdout())
            .isNotNull();
        assertThat(estimator.get("name").asText()).isEqualTo(Utf8WeightedTokenEstimator.NAME);
        assertThat(estimator.get("reference").asText()).isEqualTo(Utf8WeightedTokenEstimator.REFERENCE_TOKENIZER);
        assertThat(estimator.get("p95_rel_error").asDouble())
            .isEqualTo(Utf8WeightedTokenEstimator.PUBLISHED_P95_REL_ERROR);
    }
}
