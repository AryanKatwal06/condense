package com.condense.config;

import com.condense.core.PlatformDirs;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.trust.TrustStore;
import com.condense.trust.TrustTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigTrustCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Non-TTY without --accept prints risk and does not write trust.json")
    void nonTtyWithoutAcceptDoesNotWrite() throws Exception {
        Path projectDir = plantProject();
        Path configDir = tempDir.resolve("cfg-no-accept");
        ConfigTrustCommand cmd = command(configDir, projectDir);

        Capture capture = Capture.of(() -> cmd.call());

        assertThat(capture.exit).isEqualTo(1);
        assertThat(capture.out).contains("Required capabilities:");
        assertThat(capture.out).contains("ansi_strip");
        assertThat(capture.err).contains("non-interactive review requires --accept");
        assertThat(Files.exists(configDir.resolve("trust.json"))).isFalse();
    }

    @Test
    @DisplayName("--accept prints the buffer first and hashes those same bytes")
    void acceptPrintsBufferThenStoresHash() throws Exception {
        Path projectDir = plantProject();
        Path file = projectDir.resolve(".condense/filters.toml");
        byte[] displayed = Files.readAllBytes(file);
        Path configDir = tempDir.resolve("cfg-accept");
        ConfigTrustCommand cmd = command(configDir, projectDir);
        cmd.accept = true;

        Capture capture = Capture.of(() -> cmd.call());

        assertThat(capture.exit).isZero();
        assertThat(capture.out).contains(new String(displayed, StandardCharsets.UTF_8).trim());
        assertThat(Files.exists(configDir.resolve("trust.json"))).isTrue();
        TrustStore store = new TrustStore(TrustTestSupport.dirs(configDir));
        assertThat(store.find(file.toRealPath())).isPresent();
        assertThat(store.find(file.toRealPath()).orElseThrow().sha256())
            .isEqualTo(TrustStore.sha256Hex(displayed));
        assertThat(store.find(file.toRealPath()).orElseThrow().capabilities())
            .containsExactly("reduce");
    }

    @Test
    @DisplayName("--accept hashes the displayed buffer even if the file is swapped afterwards")
    void acceptHashesDisplayedBufferNotAReread() throws Exception {
        Path projectDir = plantProject();
        Path file = projectDir.resolve(".condense/filters.toml");
        byte[] original = Files.readAllBytes(file);
        Path configDir = tempDir.resolve("cfg-swap");
        ConfigTrustCommand cmd = command(configDir, projectDir);
        cmd.accept = true;

        Capture capture = Capture.of(cmd::call);
        Files.writeString(file, """
            schema_version = 1
            [filters."ls"]
            stages = [ { strategy = "tree_compression" } ]
            """);

        assertThat(capture.exit).isZero();
        TrustStore store = new TrustStore(TrustTestSupport.dirs(configDir));
        assertThat(store.find(file.toRealPath()).orElseThrow().sha256())
            .isEqualTo(TrustStore.sha256Hex(original));
        assertThat(store.find(file.toRealPath()).orElseThrow().sha256())
            .isNotEqualTo(TrustStore.sha256Hex(Files.readAllBytes(file)));
    }

    @Test
    @DisplayName("Accept then revoke invalidates the loader cache")
    void acceptThenRevokeInvalidatesCache() throws Exception {
        Path projectDir = plantProject();
        Path configDir = tempDir.resolve("cfg-revoke");
        PlatformDirs dirs = TrustTestSupport.dirs(configDir);
        FilterOverrideLoader loader = new FilterOverrideLoader(dirs);
        ConfigTrustCommand acceptCmd = new ConfigTrustCommand(dirs, new com.condense.trust.TrustGate(dirs), loader);
        acceptCmd.workingDirectory = projectDir;
        acceptCmd.accept = true;
        assertThat(acceptCmd.call()).isZero();

        FilterPipeline fallback = FilterPipeline.of((in, ctx) -> StageResult.continueWith("DEFAULT"));
        assertThat(loader.resolvePipeline("ls", fallback, projectDir)).isNotSameAs(fallback);

        ConfigTrustCommand revokeCmd = new ConfigTrustCommand(dirs, new com.condense.trust.TrustGate(dirs), loader);
        revokeCmd.workingDirectory = projectDir;
        revokeCmd.revoke = true;
        assertThat(revokeCmd.call()).isZero();

        assertThat(loader.resolvePipeline("ls", fallback, projectDir)).isSameAs(fallback);
    }

    @Test
    @DisplayName("--status lists trusted files")
    void statusListsTrustedFiles() throws Exception {
        Path projectDir = plantProject();
        Path configDir = tempDir.resolve("cfg-status");
        ConfigTrustCommand accept = command(configDir, projectDir);
        accept.accept = true;
        assertThat(accept.call()).isZero();

        ConfigTrustCommand status = command(configDir, projectDir);
        status.status = true;
        Capture capture = Capture.of(status::call);
        assertThat(capture.exit).isZero();
        assertThat(capture.out).contains("filters.toml");
        assertThat(capture.out).contains("sha256=");
    }

    @Test
    @DisplayName("TTY prompt yes records trust")
    void ttyPromptYesRecordsTrust() throws Exception {
        Path projectDir = plantProject();
        Path configDir = tempDir.resolve("cfg-tty");
        ConfigTrustCommand cmd = command(configDir, projectDir);
        System.setProperty("condense.test.interactive", "true");
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)));
            Capture capture = Capture.of(cmd::call);
            assertThat(capture.exit).isZero();
            assertThat(capture.out).contains("Trust this file?");
            assertThat(Files.exists(configDir.resolve("trust.json"))).isTrue();
        } finally {
            System.setIn(originalIn);
            System.clearProperty("condense.test.interactive");
        }
    }

    private Path plantProject() throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve(".condense"));
        Files.writeString(projectDir.resolve(".condense/filters.toml"), """
            schema_version = 1
            [filters."ls"]
            stages = [ { strategy = "ansi_strip" } ]
            """);
        return projectDir;
    }

    private ConfigTrustCommand command(Path configDir, Path projectDir) {
        FilesCreate(configDir);
        ConfigTrustCommand cmd = new ConfigTrustCommand(TrustTestSupport.dirs(configDir));
        cmd.workingDirectory = projectDir;
        return cmd;
    }

    private static void FilesCreate(Path configDir) {
        try {
            Files.createDirectories(configDir);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Capture(int exit, String out, String err) {
        static Capture of(java.util.concurrent.Callable<Integer> action) throws Exception {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            try {
                System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
                System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
                int exit = action.call();
                return new Capture(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }
}
