package com.condense.nativeimage;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.ProjectFingerprint;
import com.condense.core.TrackingRepository;
import com.condense.filter.pipeline.FilterIncident;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native proof that {@code condense propose} is reviewable-only inside the
 * shipped binary. Never skips.
 */
class NativeProposeIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void proposeHelpMentionsTheCommand() throws Exception {
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), "propose", "--help");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout() + result.stderr()).containsIgnoringCase("propose");
        assertThat(result.stdout() + result.stderr()).contains("filters.toml.proposed");
    }

    @Test
    void fixtureTreeProposesWithoutWritingLiveOverride() throws Exception {
        Path work = copyFixture();
        Path config = configDir();
        Path data = dataDir();
        plantAnalytics(work, config, data);

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            config, data, null, work, null,
            "propose", "--format", "json", "--root", work.toString());
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isZero();
        JsonNode report = JSON.readTree(result.stdout());
        assertThat(report.get("schema_version").asInt()).isEqualTo(1);
        assertThat(report.get("discover_recommend").toString()).contains("pnpm-install");
        String proposals = report.get("proposals").toString();
        assertThat(proposals).contains("pnpm install");
        assertThat(proposals).contains("weirdtool");
        assertThat(work.resolve(".condense").resolve("filters.toml")).doesNotExist();

        NativeBinarySupport.CliResult written = NativeBinarySupport.run(
            config, data, null, work, null,
            "propose", "--write", "--root", work.toString());
        assertThat(written.exitCode())
            .as("stdout=%s stderr=%s", written.stdout(), written.stderr())
            .isZero();
        assertThat(work.resolve(".condense").resolve("filters.toml")).doesNotExist();
        assertThat(work.resolve(".condense").resolve("filters.toml.proposed")).exists();
    }

    private void plantAnalytics(Path work, Path config, Path data) throws Exception {
        TrackingRepository tracking = new TrackingRepository(new IsolatedPlatformDirs(config, data));
        try {
            Path root = work.toRealPath();
            String cwd = root.toString();
            String project = ProjectFingerprint.of(cwd);
            for (int i = 0; i < 5; i++) {
                tracking.insert("weirdtool build --flag", project, cwd, 500, 500, 1L);
            }
            for (int i = 0; i < 3; i++) {
                tracking.insertOutcome("custom-lint", project,
                    FilterIncident.applyFallback("custom-lint", "fallback"));
            }
        } finally {
            tracking.close();
        }
    }

    private Path copyFixture() throws Exception {
        Path work = Files.createDirectories(tempDir.resolve("proj"));
        Files.createDirectories(work.resolve(".git"));
        Files.createDirectories(work.resolve("prisma"));
        Files.write(work.resolve("pnpm-lock.yaml"), load("/discover/pnpm-prisma/pnpm-lock.yaml"));
        Files.write(work.resolve("prisma").resolve("schema.prisma"),
            load("/discover/pnpm-prisma/prisma/schema.prisma"));
        return work;
    }

    private static byte[] load(String resource) throws Exception {
        try (var in = NativeProposeIT.class.getResourceAsStream(resource)) {
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
