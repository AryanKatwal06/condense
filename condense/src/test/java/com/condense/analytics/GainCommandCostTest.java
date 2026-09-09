package com.condense.analytics;

import com.condense.core.CondenseConfig;
import com.condense.core.ConfigLoader;
import com.condense.core.PlatformDirs;
import com.condense.core.TrackingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GainCommandCostTest {

    @TempDir
    Path tempDir;

    private TrackingRepository tracking;
    private GainRepository gainRepo;
    private ByteArrayOutputStream outStream;
    private ByteArrayOutputStream errStream;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        tracking = new TrackingRepository(new PlatformDirs() {
            @Override public Path resolveConfigDir() { return tempDir.resolve("config"); }
            @Override public Path resolveDataDir() { return tempDir.resolve("data"); }
            @Override public Path getConfigDir() { return ensure(tempDir.resolve("config")); }
            @Override public Path getDataDir() { return ensure(tempDir.resolve("data")); }
            private Path ensure(Path p) {
                try { Files.createDirectories(p); } catch (Exception ignored) {}
                return p;
            }
        });
        gainRepo = new GainRepository(tracking);
        tracking.insert("git status", "proj123", "/tmp/proj", 100_000, 20_000, 40L);

        outStream = new ByteArrayOutputStream();
        errStream = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outStream));
        System.setErr(new PrintStream(errStream));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        tracking.close();
    }

    @Test
    void listModelsOutputsPricingCatalog() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.listModels = true;
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("Supported LLM Models for Cost Estimation");
        assertThat(out).contains("claude-3-5-sonnet-20241022");
        assertThat(out).contains("gpt-4o-2024-11-20");
        assertThat(out).contains("gemini-1.5-pro-002");
        assertThat(out).contains("deepseek-chat");
        assertThat(out).contains("Default model: claude-3-5-sonnet-20241022");
        assertThat(out).contains("Uncertainty note: All dollar calculations inherit ±37% token estimation uncertainty.");
    }

    @Test
    void defaultModelUsedWhenNoFlagOrConfig() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.format = "json";
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("\"model\" : \"claude-3-5-sonnet-20241022\"");
        assertThat(out).contains("\"provider\" : \"Anthropic\"");
        assertThat(out).contains("\"input_rate_per_m\" : 3.0");
        // 80,000 saved * $3.00 / 1M = $0.24
        assertThat(out).contains("\"estimated_usd_saved\" : 0.24");
    }

    @Test
    void modelFlagOverridesDefault() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.modelFlag = "gpt-4o";
        cmd.format = "json";
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("\"model\" : \"gpt-4o-2024-11-20\"");
        assertThat(out).contains("\"provider\" : \"OpenAI\"");
        assertThat(out).contains("\"input_rate_per_m\" : 2.5");
        // 80,000 saved * $2.50 / 1M = $0.20
        assertThat(out).contains("\"estimated_usd_saved\" : 0.2");
    }

    @Test
    void modelAliasIsResolvedCaseInsensitively() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.modelFlag = "SONNET";
        cmd.format = "json";
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("\"model\" : \"claude-3-5-sonnet-20241022\"");
        assertThat(out).contains("\"estimated_usd_saved\" : 0.24");
    }

    @Test
    void configuredModelUsedWhenNoCliFlag() {
        ConfigLoader loader = new ConfigLoader() {
            @Override
            public CondenseConfig load() {
                return new CondenseConfig(
                    CondenseConfig.defaults().hooks(),
                    CondenseConfig.defaults().tee(),
                    Map.of(),
                    new CondenseConfig.AnalyticsConfig("gemini-1.5-flash")
                );
            }
        };

        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.configLoader = loader;
        cmd.format = "json";
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("\"model\" : \"gemini-1.5-flash-002\"");
        assertThat(out).contains("\"provider\" : \"Google\"");
    }

    @Test
    void cliFlagPrecedesConfiguredModel() {
        ConfigLoader loader = new ConfigLoader() {
            @Override
            public CondenseConfig load() {
                return new CondenseConfig(
                    CondenseConfig.defaults().hooks(),
                    CondenseConfig.defaults().tee(),
                    Map.of(),
                    new CondenseConfig.AnalyticsConfig("gemini-1.5-flash")
                );
            }
        };

        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.configLoader = loader;
        cmd.modelFlag = "deepseek-chat";
        cmd.format = "json";
        cmd.run();

        String out = outStream.toString();
        assertThat(out).contains("\"model\" : \"deepseek-chat\"");
        assertThat(out).contains("\"provider\" : \"DeepSeek\"");
    }

    @Test
    void unknownModelEmitsWarningAndSuppressesDollarEstimate() {
        GainCommand cmd = new GainCommand();
        cmd.gainRepo = gainRepo;
        cmd.modelFlag = "nonexistent-model-xyz";
        cmd.format = "json";
        cmd.run();

        String err = errStream.toString();
        assertThat(err).contains("condense gain: warning: unknown model 'nonexistent-model-xyz'");
        assertThat(err).contains("Run with --list-models to see available models");

        String out = outStream.toString();
        assertThat(out).contains("\"cost\" : null");
    }
}
