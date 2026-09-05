package com.condense.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Native-image proof that {@code condense read} scans inside the shipped binary.
 */
class NativeReadIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateDirs() throws Exception {
        Files.createDirectories(configDir());
        Files.createDirectories(dataDir());
    }

    @Test
    void readHelpMentionsTheCommand() throws Exception {
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), "read", "--help");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout() + result.stderr()).containsIgnoringCase("read");
    }

    @Test
    void commentsModeKeepsGlobStringAndRecordsGain() throws Exception {
        Path work = tempDir.resolve("proj");
        Files.createDirectories(work);
        Path file = work.resolve("app.js");
        Files.writeString(file, """
            let glob = "src/**/*";
            /* drop me */
            const y = 1;
            """);

        NativeBinarySupport.CliResult read = NativeBinarySupport.run(
            configDir(),
            dataDir(),
            null,
            work,
            null,
            "read",
            "--level",
            "comments",
            "app.js"
        );
        assertThat(read.exitCode())
            .as("stdout=%s stderr=%s", read.stdout(), read.stderr())
            .isZero();
        assertThat(read.stdout()).contains("condense[read]");
        assertThat(read.stdout()).contains("src/**/*");
        assertThat(read.stdout()).contains("const y = 1;");
        assertThat(read.stdout()).doesNotContain("drop me");

        NativeBinarySupport.CliResult gain = NativeBinarySupport.run(
            configDir(), dataDir(), "gain", "--format", "json");
        assertThat(gain.exitCode()).isZero();
        JsonNode analytics = JSON.readTree(gain.stdout());
        assertThat(analytics.get("total_commands").asLong())
            .as("read must insert a tracking row: %s", gain.stdout())
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    void jsonCommentsKeepsPackagesStar() throws Exception {
        Path work = tempDir.resolve("jsonproj");
        Files.createDirectories(work);
        Files.writeString(work.resolve("package.json"), "{\"workspaces\":[\"packages/*\"]}");

        NativeBinarySupport.CliResult read = NativeBinarySupport.run(
            configDir(),
            dataDir(),
            null,
            work,
            null,
            "read",
            "--level",
            "comments",
            "--format",
            "json",
            "package.json"
        );
        assertThat(read.exitCode())
            .as("stdout=%s stderr=%s", read.stdout(), read.stderr())
            .isZero();
        JsonNode report = JSON.readTree(read.stdout());
        assertThat(report.get("language").asText()).isEqualTo("json");
        assertThat(report.get("output").asText()).contains("packages/*");
    }

    @Test
    void unknownExtensionStaysVerbatim() throws Exception {
        Path work = tempDir.resolve("unk");
        Files.createDirectories(work);
        Files.writeString(work.resolve("notes.unknown"), "let x = \"src/**/*\";\n/* keep */\n");

        NativeBinarySupport.CliResult read = NativeBinarySupport.run(
            configDir(),
            dataDir(),
            null,
            work,
            null,
            "read",
            "--level",
            "comments",
            "notes.unknown"
        );
        assertThat(read.exitCode()).isZero();
        assertThat(read.stderr()).contains("unknown language");
        assertThat(read.stdout()).contains("/* keep */");
    }

    @Test
    void pathEscapeExitsOneWithEmptyStdout() throws Exception {
        Path work = tempDir.resolve("inside");
        Path outside = tempDir.resolve("outside.txt");
        Files.createDirectories(work);
        Files.writeString(outside, "secret");

        NativeBinarySupport.CliResult read = NativeBinarySupport.run(
            configDir(),
            dataDir(),
            null,
            work,
            null,
            "read",
            "--root",
            work.toAbsolutePath().toString(),
            outside.toAbsolutePath().toString()
        );
        assertThat(read.exitCode()).isEqualTo(1);
        assertThat(read.stdout()).isEmpty();
    }

    @Test
    void symlinkEscapeExitsOne() throws Exception {
        Path work = tempDir.resolve("linkproj");
        Files.createDirectories(work);
        Path secret = tempDir.resolve("secret-link.txt");
        Files.writeString(secret, "secret");
        Path link = work.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (Exception e) {
            assumeThat(false).as("symlinks not available").isTrue();
            return;
        }
        NativeBinarySupport.CliResult read = NativeBinarySupport.run(
            configDir(),
            dataDir(),
            null,
            work,
            null,
            "read",
            "link.txt"
        );
        assertThat(read.exitCode()).isEqualTo(1);
        assertThat(read.stdout()).isEmpty();
    }

    private Path configDir() {
        return tempDir.resolve("config");
    }

    private Path dataDir() {
        return tempDir.resolve("data");
    }
}
