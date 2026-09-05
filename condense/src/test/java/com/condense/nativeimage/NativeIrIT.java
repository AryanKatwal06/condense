package com.condense.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native-image proof that {@code --format json} emits schema-1 documents
 * and that explain JSON embeds the same records. Never skips.
 */
class NativeIrIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateDirs() throws Exception {
        Files.createDirectories(configDir());
        Files.createDirectories(dataDir());
    }

    @Test
    void stubbedPytestJsonIsSchemaOneTestDocument() throws Exception {
        Path stubDir = tempDir.resolve("bin");
        Files.createDirectories(stubDir);
        byte[] fixture = loadClasspathFixture("/fixtures/pytest/typical.txt");
        Files.write(stubDir.resolve("fixture.txt"), fixture);
        writePytestStub(stubDir);

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "pytest");

        assertThat(result.exitCode())
            .as("proxied pytest exit must pass through: stdout=%s stderr=%s",
                result.stdout(), result.stderr())
            .isEqualTo(1);
        JsonNode document = JSON.readTree(result.stdout());
        assertThat(document.get("schema_version").asInt()).isEqualTo(1);
        assertThat(document.get("kind").asText()).isEqualTo("test");
        assertThat(document.get("child_exit_code").asInt()).isEqualTo(1);
        assertThat(document.get("was_filtered").asBoolean()).isTrue();
        assertThat(result.stdout()).contains("test_mul");
    }

    @Test
    void stubbedNpmInstallJsonWaitsForOneObject() throws Exception {
        Path stubDir = tempDir.resolve("npm-bin");
        Files.createDirectories(stubDir);
        if (NativeBinarySupport.isWindows()) {
            Files.writeString(stubDir.resolve("npm.cmd"),
                "@echo off\r\n"
                    + "echo npm warn deprecated foo@1.0.0: gone\r\n"
                    + "echo added 12 packages in 1s\r\n"
                    + "echo found 3 vulnerabilities\r\n"
                    + "exit /b 0\r\n");
        } else {
            Path stub = stubDir.resolve("npm");
            Files.writeString(stub,
                "#!/bin/sh\n"
                    + "echo 'npm warn deprecated foo@1.0.0: gone'\n"
                    + "echo 'added 12 packages in 1s'\n"
                    + "echo 'found 3 vulnerabilities'\n"
                    + "exit 0\n");
            stub.toFile().setExecutable(true);
        }

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "npm", "install");
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isZero();
        JsonNode document = JSON.readTree(result.stdout());
        assertThat(document.get("schema_version").asInt()).isEqualTo(1);
        assertThat(document.get("kind").asText()).isEqualTo("dependency");
        assertThat(document.get("document").get("added_packages").asInt()).isEqualTo(12);
        String warnText = document.get("document").toString();
        assertThat(warnText).contains("npm warn");
        assertThat(result.stdout().trim()).startsWith("{");
        assertThat(result.stdout().trim()).endsWith("}");
    }

    @Test
    void stubbedEslintJsonIsDiagnostic() throws Exception {
        Path stubDir = tempDir.resolve("eslint-bin");
        Files.createDirectories(stubDir);
        Files.write(stubDir.resolve("fixture.txt"), loadClasspathFixture("/fixtures/eslint/typical.txt"));
        writeStub(stubDir, "eslint", 1);
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "eslint");
        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode document = JSON.readTree(result.stdout());
        assertThat(document.get("kind").asText()).isEqualTo("diagnostic");
        assertThat(document.get("document").get("findings").isArray()).isTrue();
        assertThat(document.get("document").get("findings").size()).isGreaterThan(0);
    }

    @Test
    void stubbedDockerPsJsonIsResource() throws Exception {
        Path stubDir = tempDir.resolve("docker-bin");
        Files.createDirectories(stubDir);
        Files.write(stubDir.resolve("fixture.txt"), loadClasspathFixture("/fixtures/docker-ps/typical.txt"));
        writeStub(stubDir, "docker", 0);
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "docker", "ps");
        assertThat(result.exitCode()).isZero();
        JsonNode document = JSON.readTree(result.stdout());
        assertThat(document.get("kind").asText()).isEqualTo("resource");
    }

    @Test
    void stubbedGitStatusJsonIsOpaque() throws Exception {
        Path stubDir = tempDir.resolve("git-bin");
        Files.createDirectories(stubDir);
        Files.write(stubDir.resolve("fixture.txt"), loadClasspathFixture("/fixtures/git-status/mixed.txt"));
        writeStub(stubDir, "git", 0);
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "git", "status");
        assertThat(result.exitCode()).isZero();
        JsonNode document = JSON.readTree(result.stdout());
        assertThat(document.get("kind").asText()).isEqualTo("opaque");
        assertThat(document.get("document").get("body").asText()).isNotBlank();
    }

    @Test
    void explainJsonIncludesPytestDocumentKind() throws Exception {
        Path fixture = tempDir.resolve("pytest.txt");
        Files.write(fixture, loadClasspathFixture("/fixtures/pytest/typical.txt"));
        NativeBinarySupport.CliResult explained = NativeBinarySupport.run(
            configDir(),
            dataDir(),
            "explain",
            "--input",
            fixture.toAbsolutePath().toString(),
            "--exit-code",
            "1",
            "--format",
            "json",
            "pytest"
        );
        assertThat(explained.exitCode())
            .as("stdout=%s stderr=%s", explained.stdout(), explained.stderr())
            .isZero();
        JsonNode report = JSON.readTree(explained.stdout());
        assertThat(report.get("document").get("kind").asText()).isEqualTo("test");
        assertThat(report.get("document").get("schema_version").asInt()).isEqualTo(1);
    }

    private static void writeStub(Path stubDir, String name, int exit) throws Exception {
        if (NativeBinarySupport.isWindows()) {
            Files.writeString(stubDir.resolve(name + ".cmd"),
                "@echo off\r\ntype \"%~dp0fixture.txt\"\r\nexit /b " + exit + "\r\n",
                StandardCharsets.UTF_8);
            return;
        }
        Path script = stubDir.resolve(name);
        Files.writeString(script, "#!/bin/sh\ncat \"$(dirname \"$0\")/fixture.txt\"\nexit " + exit + "\n",
            StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            script.toFile().setExecutable(true);
        }
    }

    private static void writePytestStub(Path stubDir) throws Exception {
        if (NativeBinarySupport.isWindows()) {
            Files.writeString(stubDir.resolve("pytest.cmd"),
                "@echo off\r\ntype \"%~dp0fixture.txt\"\r\nexit /b 1\r\n",
                StandardCharsets.UTF_8);
            return;
        }
        Path script = stubDir.resolve("pytest");
        Files.writeString(script, "#!/bin/sh\ncat \"$(dirname \"$0\")/fixture.txt\"\nexit 1\n",
            StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            script.toFile().setExecutable(true);
        }
    }

    private static byte[] loadClasspathFixture(String resource) throws Exception {
        try (var in = NativeIrIT.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource + " must be on the test classpath").isNotNull();
            return in.readAllBytes();
        }
    }

    private Path configDir() {
        return tempDir.resolve("config");
    }

    private Path dataDir() {
        return tempDir.resolve("data");
    }
}
