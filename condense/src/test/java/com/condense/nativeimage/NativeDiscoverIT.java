package com.condense.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native proof that {@code condense discover} reads a contained fixture tree
 * inside the shipped binary. Never skips.
 */
class NativeDiscoverIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void discoverHelpMentionsTheCommand() throws Exception {
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), "discover", "--help");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout() + result.stderr()).containsIgnoringCase("discover");
    }

    @Test
    void fixtureTreeRecommendsPnpmAndPrismaUnderCaps() throws Exception {
        Path work = copyFixture();
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), null, work, null,
            "discover", "--format", "json", "--root", work.toString());
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isZero();
        JsonNode report = JSON.readTree(result.stdout());
        assertThat(report.get("schema_version").asInt()).isEqualTo(1);
        String recommend = report.get("recommend").toString();
        assertThat(recommend).contains("pnpm-install");
        assertThat(recommend).contains("prisma");
        assertThat(report.get("files_probed").asInt()).isLessThanOrEqualTo(64);
        assertThat(report.get("bytes_read").asLong()).isLessThanOrEqualTo(256L * 1024);
        assertThat(report.get("truncated").asBoolean()).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void symlinkEscapeIsNotRecommended() throws Exception {
        Path work = Files.createDirectories(tempDir.resolve("escape"));
        Files.createDirectories(work.resolve(".git"));
        Path outside = tempDir.resolve("secret-package.json");
        Files.writeString(outside, "{}\n");
        Files.createSymbolicLink(work.resolve("package.json"), outside);

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), null, work, null,
            "discover", "--format", "json", "--root", work.toString());
        assertThat(result.exitCode()).isZero();
        JsonNode report = JSON.readTree(result.stdout());
        assertThat(report.get("recommend").toString()).doesNotContain("npm-install");
    }

    private Path copyFixture() throws Exception {
        Path work = Files.createDirectories(tempDir.resolve("proj"));
        Files.createDirectories(work.resolve(".git"));
        Files.createDirectories(work.resolve("prisma"));
        byte[] lock = load("/discover/pnpm-prisma/pnpm-lock.yaml");
        byte[] schema = load("/discover/pnpm-prisma/prisma/schema.prisma");
        Files.write(work.resolve("pnpm-lock.yaml"), lock);
        Files.write(work.resolve("prisma").resolve("schema.prisma"), schema);
        return work;
    }

    private static byte[] load(String resource) throws Exception {
        try (var in = NativeDiscoverIT.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource + " must be on the test classpath").isNotNull();
            return in.readAllBytes();
        }
    }

    private Path configDir() throws Exception {
        Path dir = tempDir.resolve("config");
        Files.createDirectories(dir);
        return dir;
    }

    private Path dataDir() throws Exception {
        Path dir = tempDir.resolve("data");
        Files.createDirectories(dir);
        return dir;
    }
}
