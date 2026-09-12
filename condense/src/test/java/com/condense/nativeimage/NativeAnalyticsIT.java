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

        // Native pricing catalog and cost estimation
        JsonNode cost = report.get("cost");
        assertThat(cost)
            .as("gain JSON must include default cost estimate in native image: %s", gain.stdout())
            .isNotNull();
        assertThat(cost.get("model").asText()).isEqualTo("claude-3-5-sonnet-20241022");
        assertThat(cost.get("currency").asText()).isEqualTo("USD");

        // Native --model override
        NativeBinarySupport.CliResult gainGpt = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--format", "json", "--model", "gpt-4o"
        );
        assertThat(gainGpt.exitCode()).isZero();
        JsonNode gptReport = JSON.readTree(gainGpt.stdout());
        assertThat(gptReport.get("cost").get("model").asText()).isEqualTo("gpt-4o-2024-11-20");
        assertThat(gptReport.get("cost").get("provider").asText()).isEqualTo("OpenAI");

        // Native --list-models
        NativeBinarySupport.CliResult listModels = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--list-models"
        );
        assertThat(listModels.exitCode()).isZero();
        assertThat(listModels.stdout()).contains("Supported LLM Models for Cost Estimation");
        assertThat(listModels.stdout()).contains("claude-3-5-sonnet-20241022");
        assertThat(listModels.stdout()).contains("Default model: claude-3-5-sonnet-20241022");

        // Native CSV export includes cost fields
        NativeBinarySupport.CliResult gainCsv = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--format", "csv"
        );
        assertThat(gainCsv.exitCode()).isZero();
        assertThat(gainCsv.stdout()).contains("cost_model,claude-3-5-sonnet-20241022");
        assertThat(gainCsv.stdout()).contains("estimated_usd_saved,");

        // Native --trend text table output
        NativeBinarySupport.CliResult gainTrend = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--trend"
        );
        assertThat(gainTrend.exitCode()).isZero();
        assertThat(gainTrend.stdout()).contains("Week");
        assertThat(gainTrend.stdout()).contains("Cmds");
        assertThat(gainTrend.stdout()).contains("Total (8w)");

        // Native --trend JSON output
        NativeBinarySupport.CliResult gainTrendJson = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--trend", "--format", "json"
        );
        assertThat(gainTrendJson.exitCode()).isZero();
        JsonNode trendReport = JSON.readTree(gainTrendJson.stdout());
        assertThat(trendReport.get("weeks_requested").asInt()).isEqualTo(8);
        assertThat(trendReport.get("weeks").isArray()).isTrue();
        assertThat(trendReport.get("weeks")).hasSize(8);

        // Native --gaps text output
        NativeBinarySupport.CliResult gainGaps = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--gaps"
        );
        assertThat(gainGaps.exitCode()).isZero();
        assertThat(gainGaps.stderr()).doesNotContain("analytics unavailable");
        assertThat(gainGaps.stdout()).contains("No gap candidates found");

        // Native --gaps JSON output
        NativeBinarySupport.CliResult gainGapsJson = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--gaps", "--format", "json"
        );
        assertThat(gainGapsJson.exitCode()).isZero();
        JsonNode gapsJson = JSON.readTree(gainGapsJson.stdout());
        assertThat(gapsJson.isArray()).isTrue();

        // Native --gaps CSV output
        NativeBinarySupport.CliResult gainGapsCsv = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--gaps", "--format", "csv"
        );
        assertThat(gainGapsCsv.exitCode()).isZero();
        assertThat(gainGapsCsv.stdout()).contains("command_prefix,invocations,raw_tokens,filtered_tokens,wasted_tokens,savings_pct");
    }
}
