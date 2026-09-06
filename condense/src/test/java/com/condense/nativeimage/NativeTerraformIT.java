package com.condense.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native proof that leftover Terraform/OpenTofu pipelines parse machine
 * output, keep human-text grouping, and leave {@code state show} unmatched.
 * Never skips.
 */
class NativeTerraformIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void stubbedTerraformPlanJsonIsResourceAndKeepsSignals() throws Exception {
        Path stubDir = writeStub("plan-bin", "terraform", "/fixtures/terraform/plan-json.ndjson", 1);
        NativeBinarySupport.CliResult text = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "terraform", "plan");
        assertThat(text.exitCode())
            .as("stdout=%s stderr=%s", text.stdout(), text.stderr())
            .isEqualTo(1);
        assertThat(text.stdout())
            .startsWith("condense[filtered]")
            .contains("aws_instance.web")
            .contains("aws_eip.old")
            .contains("Plan:")
            .contains("Missing required argument");

        NativeBinarySupport.CliResult json = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "terraform", "plan");
        assertThat(json.exitCode()).isEqualTo(1);
        JsonNode document = JSON.readTree(json.stdout());
        assertThat(document.get("schema_version").asInt()).isEqualTo(1);
        assertThat(document.get("kind").asText()).isEqualTo("resource");
        assertThat(document.get("document").get("format").asText()).isEqualTo("infra");
    }

    @Test
    void stubbedTerraformValidateJsonIsDiagnostic() throws Exception {
        Path stubDir = writeStub("validate-bin", "terraform", "/fixtures/terraform/validate-json.json", 1);
        NativeBinarySupport.CliResult text = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "terraform", "validate");
        assertThat(text.exitCode()).isEqualTo(1);
        assertThat(text.stdout())
            .startsWith("condense[filtered]")
            .contains("Invalid resource type")
            .contains("main.tf");

        NativeBinarySupport.CliResult json = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "terraform", "validate");
        assertThat(json.exitCode()).isEqualTo(1);
        JsonNode document = JSON.readTree(json.stdout());
        assertThat(document.get("kind").asText()).isEqualTo("diagnostic");
        assertThat(document.get("document").get("tool").asText()).isEqualTo("validate");
    }

    @Test
    void stubbedTerraformTypicalHumanTextIsOpaque() throws Exception {
        Path stubDir = writeStub("typical-bin", "terraform", "/fixtures/terraform/typical.txt", 1);
        NativeBinarySupport.CliResult text = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "terraform", "plan");
        assertThat(text.exitCode()).isEqualTo(1);
        assertThat(text.stdout())
            .startsWith("condense[filtered]")
            .contains("aws_instance.web")
            .contains("Error: Missing required argument")
            .contains("Plan:");

        NativeBinarySupport.CliResult json = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "terraform", "plan");
        assertThat(json.exitCode()).isEqualTo(1);
        JsonNode document = JSON.readTree(json.stdout());
        assertThat(document.get("kind").asText()).isEqualTo("opaque");
    }

    @Test
    void stubbedTofuPlanJsonIsResource() throws Exception {
        Path stubDir = writeStub("tofu-bin", "tofu", "/fixtures/tofu/plan-json.ndjson", 0);
        NativeBinarySupport.CliResult text = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "tofu", "plan");
        assertThat(text.exitCode()).isZero();
        assertThat(text.stdout())
            .startsWith("condense[filtered]")
            .contains("aws_instance.web")
            .contains("Plan:");

        NativeBinarySupport.CliResult json = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "tofu", "plan");
        assertThat(json.exitCode()).isZero();
        JsonNode document = JSON.readTree(json.stdout());
        assertThat(document.get("kind").asText()).isEqualTo("resource");
    }

    @Test
    void stubbedTerraformStateShowIsPassthrough() throws Exception {
        Path stubDir = tempDir.resolve("show-bin");
        Files.createDirectories(stubDir);
        String body = "id = i-0123456789abcdef0\nami = ami-123\n";
        if (NativeBinarySupport.isWindows()) {
            Files.writeString(stubDir.resolve("terraform.cmd"),
                "@echo off\r\n"
                    + "echo id = i-0123456789abcdef0\r\n"
                    + "echo ami = ami-123\r\n"
                    + "exit /b 0\r\n",
                StandardCharsets.UTF_8);
        } else {
            Path script = stubDir.resolve("terraform");
            Files.writeString(script,
                "#!/bin/sh\nprintf '%s' '" + body + "'\nexit 0\n",
                StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException ignored) {
                script.toFile().setExecutable(true);
            }
        }

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "terraform", "state", "show", "aws_instance.web");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout())
            .doesNotStartWith("condense[filtered]")
            .contains("i-0123456789abcdef0")
            .contains("ami-123");
    }

    private Path writeStub(String dirName, String command, String fixture, int exit) throws Exception {
        Path stubDir = tempDir.resolve(dirName);
        Files.createDirectories(stubDir);
        Files.write(stubDir.resolve("fixture.txt"), loadClasspathFixture(fixture));
        if (NativeBinarySupport.isWindows()) {
            Files.writeString(stubDir.resolve(command + ".cmd"),
                "@echo off\r\ntype \"%~dp0fixture.txt\"\r\nexit /b " + exit + "\r\n",
                StandardCharsets.UTF_8);
        } else {
            Path script = stubDir.resolve(command);
            Files.writeString(script,
                "#!/bin/sh\ncat \"$(dirname \"$0\")/fixture.txt\"\nexit " + exit + "\n",
                StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException ignored) {
                script.toFile().setExecutable(true);
            }
        }
        return stubDir;
    }

    private static byte[] loadClasspathFixture(String resource) throws Exception {
        try (var in = NativeTerraformIT.class.getResourceAsStream(resource)) {
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
