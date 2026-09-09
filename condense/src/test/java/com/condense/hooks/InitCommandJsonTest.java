package com.condense.hooks;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.Mappers;
import com.condense.core.TrackingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InitCommandJsonTest {

    @TempDir
    Path tempDir;

    private ByteArrayOutputStream outStream;
    private PrintStream originalOut;
    private TrackingRepository tracking;

    @BeforeEach
    void setUp() {
        outStream = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(outStream));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        if (tracking != null) {
            tracking.close();
        }
    }

    @Test
    @DisplayName("InitCommand --show --format json outputs structured JSON object with hooks array")
    void testInitShowJsonFormat() throws Exception {
        System.setProperty("condense.test.home", tempDir.toAbsolutePath().toString());
        try {
            IsolatedPlatformDirs dirs = new IsolatedPlatformDirs(tempDir.resolve("cfg"), tempDir.resolve("data"));
            tracking = new TrackingRepository(dirs);

            HookInstaller installer = new HookInstaller();
            installer.configLoader = new EmptyConfigLoader();
            installer.platformDirs = dirs;
            installer.tracking = tracking;

            // Install one tool hook so we have at least one installed
            installer.install(HookTool.CURSOR);

            InitCommand cmd = new InitCommand();
            cmd.installer = installer;

            int exitCode = new CommandLine(cmd).execute("--show", "--format", "json");
            assertThat(exitCode).isEqualTo(0);

            String output = outStream.toString().trim();
            JsonNode root = Mappers.JSON.readTree(output);

            assertThat(root.has("hooks")).isTrue();
            assertThat(root.has("tampered")).isTrue();
            assertThat(root.get("hooks").isArray()).isTrue();

            boolean foundCursor = false;
            for (JsonNode item : root.get("hooks")) {
                assertThat(item.has("tool")).isTrue();
                assertThat(item.has("display_name")).isTrue();
                assertThat(item.has("installed")).isTrue();
                assertThat(item.has("integrity")).isTrue();
                assertThat(item.has("hook_file")).isTrue();
                if ("cursor".equals(item.get("tool").asText())) {
                    foundCursor = true;
                    assertThat(item.get("installed").asBoolean()).isTrue();
                }
            }
            assertThat(foundCursor).isTrue();
        } finally {
            System.clearProperty("condense.test.home");
        }
    }

    static final class EmptyConfigLoader extends com.condense.core.ConfigLoader {
        @Override
        public com.condense.core.CondenseConfig load() {
            return new com.condense.core.CondenseConfig(
                new com.condense.core.CondenseConfig.HooksConfig(List.of()),
                new com.condense.core.CondenseConfig.TeeConfig(true, com.condense.core.TeeMode.FAILURES),
                java.util.Map.of()
            );
        }
    }
}
