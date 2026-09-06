package com.condense.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native proof that leftover .NET pipelines parse sidecar binlog, TRX, and
 * format-report artifacts, keep human-text grouping, and never inject a TRX
 * logger. Never skips.
 */
class NativeDotnetIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] USER_MARKER = "USER-BINLOG-MARKER".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    void stubbedDotnetBuildBinlogIsDiagnostic() throws Exception {
        Path stubDir = writeStub("build-bin", "dotnet", "/fixtures/dotnet-build/typical.txt", 1, false, null);
        NativeBinarySupport.CliResult text = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "dotnet", "build");
        assertThat(text.exitCode())
            .as("stdout=%s stderr=%s", text.stdout(), text.stderr())
            .isEqualTo(1);
        assertThat(text.stdout())
            .startsWith("condense[filtered]")
            .contains("CS0029")
            .contains("Cannot implicitly convert type");
        assertThat(readArgv(stubDir)).doesNotContain("logger").doesNotContain("report-trx");

        NativeBinarySupport.CliResult json = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "dotnet", "build");
        assertThat(json.exitCode()).isEqualTo(1);
        JsonNode document = JSON.readTree(json.stdout());
        assertThat(document.get("schema_version").asInt()).isEqualTo(1);
        assertThat(document.get("kind").asText()).isEqualTo("diagnostic");
        assertThat(document.get("document").get("tool").asText()).isEqualTo("msbuild");
    }

    @Test
    void stubbedMsbuildBinlogIsDiagnostic() throws Exception {
        Path stubDir = writeStub("msbuild-bin", "msbuild", "/fixtures/dotnet-build/typical.txt", 1, false, null);
        NativeBinarySupport.CliResult json = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "msbuild");
        assertThat(json.exitCode()).isEqualTo(1);
        JsonNode document = JSON.readTree(json.stdout());
        assertThat(document.get("kind").asText()).isEqualTo("diagnostic");
        assertThat(document.get("document").get("tool").asText()).isEqualTo("msbuild");
        assertThat(json.stdout()).contains("CS0029");
    }

    @Test
    void stubbedDotnetFormatReportIsDiagnostic() throws Exception {
        Path stubDir = writeStub("format-bin", "dotnet", "/fixtures/dotnet-format/typical.txt", 1, false, null);
        NativeBinarySupport.CliResult text = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "dotnet", "format");
        assertThat(text.exitCode()).isEqualTo(1);
        assertThat(text.stdout())
            .startsWith("condense[filtered]")
            .contains("IDE0055")
            .contains("IDE0005");

        NativeBinarySupport.CliResult json = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "dotnet", "format");
        assertThat(json.exitCode()).isEqualTo(1);
        JsonNode document = JSON.readTree(json.stdout());
        assertThat(document.get("kind").asText()).isEqualTo("diagnostic");
        assertThat(document.get("document").get("tool").asText()).isEqualTo("dotnet-format");
    }

    @Test
    void stubbedDotnetTestTrxIsTest() throws Exception {
        Path stubDir = writeStub("test-trx", "dotnet", "/fixtures/dotnet-test/typical.txt", 1, false, "trx");
        Path workDir = tempDir.resolve("trx-work");
        Files.createDirectories(workDir);
        NativeBinarySupport.CliResult text = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, workDir, null, "dotnet", "test");
        assertThat(text.exitCode()).isEqualTo(1);
        assertThat(text.stdout())
            .startsWith("condense[filtered]")
            .contains("TestInvoiceTotal")
            .contains("TestAuthSession");

        NativeBinarySupport.CliResult json = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, workDir, null, "--format", "json", "dotnet", "test");
        assertThat(json.exitCode()).isEqualTo(1);
        JsonNode document = JSON.readTree(json.stdout());
        assertThat(document.get("kind").asText()).isEqualTo("test");
        assertThat(document.get("document").get("tool").asText()).isEqualTo("trx");
        assertThat(readArgv(stubDir).toLowerCase(Locale.ROOT))
            .doesNotContain("--logger")
            .doesNotContain("report-trx");
    }

    @Test
    void stubbedDotnetBuildTypicalHumanIsOpaque() throws Exception {
        Path stubDir = writeStub("typical-bin", "dotnet", "/fixtures/dotnet-build/typical.txt", 1, true, null);
        NativeBinarySupport.CliResult text = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "dotnet", "build");
        assertThat(text.exitCode()).isEqualTo(1);
        assertThat(text.stdout())
            .startsWith("condense[filtered]")
            .contains("CS0029")
            .contains("Build FAILED");

        NativeBinarySupport.CliResult json = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, "--format", "json", "dotnet", "build");
        assertThat(json.exitCode()).isEqualTo(1);
        JsonNode document = JSON.readTree(json.stdout());
        assertThat(document.get("kind").asText()).isEqualTo("opaque");
    }

    @Test
    void collisionDoesNotOverwriteUserBinlog() throws Exception {
        Path stubDir = writeStub("collision-bin", "dotnet", "/fixtures/dotnet-build/typical.txt", 1, false, null);
        Path workDir = tempDir.resolve("collision-work");
        Files.createDirectories(workDir);
        Path userBinlog = workDir.resolve("user.binlog");
        Files.write(userBinlog, USER_MARKER);

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, workDir, null,
            "dotnet", "build", "-bl:user.binlog");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readAllBytes(userBinlog)).isEqualTo(USER_MARKER);
        assertThat(readArgv(stubDir)).contains("-bl:user.binlog");
        assertThat(countBlFlags(readArgv(stubDir))).isEqualTo(1);
    }

    @Test
    void xxeDoesNotLeakSecret() throws Exception {
        Path stubDir = writeStub("xxe-bin", "dotnet", "/fixtures/dotnet-test/typical.txt", 1, true, "xxe");
        Path workDir = tempDir.resolve("xxe-work");
        Files.createDirectories(workDir);
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), stubDir, workDir, null, "dotnet", "test");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout() + result.stderr())
            .doesNotContain("[fonts]")
            .doesNotContain("for 16-bit app support")
            .doesNotContain("win.ini");
    }

    private Path writeStub(
            String dirName,
            String command,
            String consoleFixture,
            int exit,
            boolean humanOnly,
            String trxMode
    ) throws Exception {
        Path stubDir = tempDir.resolve(dirName);
        Files.createDirectories(stubDir);
        Files.writeString(stubDir.resolve("exitcode.txt"), String.valueOf(exit), StandardCharsets.UTF_8);
        Files.write(stubDir.resolve("console.txt"), loadClasspath(consoleFixture));
        Files.write(stubDir.resolve("typical.binlog"), loadClasspath("/fixtures/dotnet-build/typical.binlog"));
        Files.write(stubDir.resolve("format-report.json"), loadClasspath("/fixtures/dotnet-format/format-report.json"));
        Files.write(stubDir.resolve("trx-failed.xml"), loadClasspath("/fixtures/dotnet-test/trx-failed.xml"));
        Files.write(stubDir.resolve("trx-xxe.xml"), loadClasspath("/fixtures/dotnet-test/trx-xxe.xml"));
        if (humanOnly) {
            Files.writeString(stubDir.resolve("human-only.flag"), "1", StandardCharsets.UTF_8);
        }
        if (trxMode != null) {
            Files.writeString(stubDir.resolve("write-" + trxMode + ".flag"), "1", StandardCharsets.UTF_8);
        }
        if (NativeBinarySupport.isWindows()) {
            Files.writeString(stubDir.resolve(command + ".cmd"), windowsStub(), StandardCharsets.UTF_8);
        } else {
            Path script = stubDir.resolve(command);
            Files.writeString(script, unixStub(), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException ignored) {
                script.toFile().setExecutable(true);
            }
        }
        return stubDir;
    }

    private static String windowsStub() {
        return """
            @echo off
            setlocal EnableDelayedExpansion
            set "HERE=%~dp0"
            set "EXITCODE=1"
            if exist "%HERE%exitcode.txt" set /p EXITCODE=<"%HERE%exitcode.txt"
            set "WRITE_ARTIFACTS=1"
            if exist "%HERE%human-only.flag" set "WRITE_ARTIFACTS=0"
            echo %* >"%HERE%argv.txt"
            :parse
            if "%~1"=="" goto after
            set "ARG=%~1"
            if /I "!ARG:~0,4!"=="-bl:" (
              set "DEST=!ARG:~4!"
              if "!WRITE_ARTIFACTS!"=="1" if not exist "!DEST!" copy /Y "%HERE%typical.binlog" "!DEST!" >nul
            )
            if /I "!ARG:~0,4!"=="/bl:" (
              set "DEST=!ARG:~4!"
              if "!WRITE_ARTIFACTS!"=="1" if not exist "!DEST!" copy /Y "%HERE%typical.binlog" "!DEST!" >nul
            )
            if /I "!ARG!"=="--report" (
              set "RDIR=%~2"
              if "!WRITE_ARTIFACTS!"=="1" (
                if not exist "!RDIR!" mkdir "!RDIR!"
                if not exist "!RDIR!\\format-report.json" copy /Y "%HERE%format-report.json" "!RDIR!\\format-report.json" >nul
              )
              shift
            )
            shift
            goto parse
            :after
            if exist "%HERE%write-trx.flag" (
              if not exist "TestResults" mkdir TestResults
              copy /Y "%HERE%trx-failed.xml" "TestResults\\results.trx" >nul
            )
            if exist "%HERE%write-xxe.flag" (
              if not exist "TestResults" mkdir TestResults
              copy /Y "%HERE%trx-xxe.xml" "TestResults\\xxe.trx" >nul
            )
            type "%HERE%console.txt"
            exit /b %EXITCODE%
            """;
    }

    private static String unixStub() {
        return """
            #!/bin/sh
            HERE=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
            EXIT=1
            [ -f "$HERE/exitcode.txt" ] && EXIT=$(tr -d '\\r' < "$HERE/exitcode.txt")
            WRITE=1
            [ -f "$HERE/human-only.flag" ] && WRITE=0
            printf '%s\\n' "$@" > "$HERE/argv.txt"
            while [ $# -gt 0 ]; do
              dest=""
              case "$1" in
                -bl:*) dest="${1#-bl:}" ;;
                /bl:*) dest="${1#/bl:}" ;;
                --report)
                  rdir="$2"
                  shift
                  if [ "$WRITE" = 1 ] && [ -n "$rdir" ]; then
                    mkdir -p "$rdir"
                    [ -f "$rdir/format-report.json" ] || cp "$HERE/format-report.json" "$rdir/format-report.json"
                  fi
                  ;;
              esac
              if [ -n "$dest" ] && [ "$WRITE" = 1 ] && [ ! -f "$dest" ]; then
                mkdir -p "$(dirname "$dest")"
                cp "$HERE/typical.binlog" "$dest"
              fi
              shift
            done
            if [ -f "$HERE/write-trx.flag" ]; then
              mkdir -p TestResults
              cp "$HERE/trx-failed.xml" TestResults/results.trx
            fi
            if [ -f "$HERE/write-xxe.flag" ]; then
              mkdir -p TestResults
              cp "$HERE/trx-xxe.xml" TestResults/xxe.trx
            fi
            cat "$HERE/console.txt"
            exit "$EXIT"
            """;
    }

    private static String readArgv(Path stubDir) throws Exception {
        Path file = stubDir.resolve("argv.txt");
        if (!Files.exists(file)) {
            return "";
        }
        return Files.readString(file);
    }

    private static int countBlFlags(String argv) {
        int count = 0;
        String lower = argv.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int at = lower.indexOf("-bl", from);
            if (at < 0) {
                at = lower.indexOf("/bl", from);
            }
            if (at < 0) {
                return count;
            }
            count++;
            from = at + 3;
        }
    }

    private static byte[] loadClasspath(String resource) throws Exception {
        try (var in = NativeDotnetIT.class.getResourceAsStream(resource)) {
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
