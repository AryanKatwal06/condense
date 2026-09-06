package com.condense.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SidecarArgvPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void injectsBinlogWhenAbsent() {
        SidecarArgvPolicy.Decision decision = SidecarArgvPolicy.prepare(List.of("dotnet", "build"));
        try {
            assertThat(decision.injected()).isTrue();
            assertThat(decision.launchArgs()).hasSize(3);
            assertThat(decision.launchArgs().get(2)).startsWith("-bl:");
            assertThat(decision.launchArgs().get(2)).contains("msbuild.binlog");
            assertThat(decision.launchArgs()).noneMatch(arg -> arg.contains("trx"));
            assertThat(decision.sidecarDir().getFileName().toString()).startsWith("condense-dotnet-");
        } finally {
            SidecarArgvPolicy.cleanup(decision);
        }
    }

    @Test
    void injectsFormatReportWhenAbsent() {
        SidecarArgvPolicy.Decision decision = SidecarArgvPolicy.prepare(List.of("dotnet", "format"));
        try {
            assertThat(decision.injected()).isTrue();
            assertThat(decision.launchArgs()).contains("--report");
            assertThat(decision.launchArgs()).doesNotContain("-bl:" + decision.sidecarDir());
            assertThat(String.join(" ", decision.launchArgs())).doesNotContain("trx");
        } finally {
            SidecarArgvPolicy.cleanup(decision);
        }
    }

    @Test
    void doesNotInjectOnExistingBinlogAndKeepsUserPath() {
        Path existing = tempDir.resolve("user.binlog");
        SidecarArgvPolicy.Decision decision = SidecarArgvPolicy.prepare(
            List.of("dotnet", "build", "-bl:" + existing));
        try {
            assertThat(decision.injected()).isFalse();
            assertThat(decision.launchArgs()).isEqualTo(List.of("dotnet", "build", "-bl:" + existing));
            assertThat(decision.namedArtifacts()).contains(existing);
        } finally {
            SidecarArgvPolicy.cleanup(decision);
        }
    }

    @Test
    void doesNotInjectOnHelp() {
        SidecarArgvPolicy.Decision decision = SidecarArgvPolicy.prepare(List.of("dotnet", "test", "--help"));
        assertThat(decision.injected()).isFalse();
        assertThat(decision.launchArgs()).isEqualTo(List.of("dotnet", "test", "--help"));
    }

    @Test
    void injectsBeforeDashDash() {
        SidecarArgvPolicy.Decision decision = SidecarArgvPolicy.prepare(
            List.of("dotnet", "test", "--", "--filter-method", "X"));
        try {
            assertThat(decision.injected()).isTrue();
            int dash = decision.launchArgs().indexOf("--");
            assertThat(dash).isGreaterThan(0);
            assertThat(decision.launchArgs().get(dash - 1)).startsWith("-bl:");
            assertThat(decision.launchArgs().subList(dash, decision.launchArgs().size()))
                .containsExactly("--", "--filter-method", "X");
        } finally {
            SidecarArgvPolicy.cleanup(decision);
        }
    }

    @Test
    void neverAddsTrxLogger() {
        SidecarArgvPolicy.Decision decision = SidecarArgvPolicy.prepare(List.of("dotnet", "test"));
        try {
            assertThat(decision.launchArgs()).noneMatch(arg -> arg.toLowerCase().contains("trx"));
            assertThat(decision.launchArgs()).noneMatch(arg -> arg.contains("--logger"));
            assertThat(decision.launchArgs()).noneMatch(arg -> arg.contains("--report-trx"));
        } finally {
            SidecarArgvPolicy.cleanup(decision);
        }
    }

    @Test
    void leavesDotnetRunAlone() {
        SidecarArgvPolicy.Decision decision = SidecarArgvPolicy.prepare(List.of("dotnet", "run"));
        assertThat(decision.injected()).isFalse();
        assertThat(decision.launchArgs()).isEqualTo(List.of("dotnet", "run"));
    }

    @Test
    void keepsUnicodeBinlogPath() {
        Path unicode = tempDir.resolve("лог.binlog");
        SidecarArgvPolicy.Decision decision = SidecarArgvPolicy.prepare(
            List.of("dotnet", "msbuild", "-bl:" + unicode));
        try {
            assertThat(decision.injected()).isFalse();
            assertThat(decision.namedArtifacts()).contains(unicode);
            assertThat(decision.launchArgs().get(2)).contains("лог.binlog");
        } finally {
            SidecarArgvPolicy.cleanup(decision);
        }
    }

    @Test
    void collectReadsSidecarAndExistingTestResults() throws Exception {
        SidecarArgvPolicy.Decision decision = SidecarArgvPolicy.prepare(List.of("dotnet", "build"));
        try {
            Files.writeString(decision.sidecarDir().resolve(SidecarArgvPolicy.BINLOG_NAME), "bin");
            Path results = tempDir.resolve("TestResults");
            Files.createDirectories(results);
            Path trx = results.resolve("failed.trx");
            Files.writeString(trx, "<TestRun/>");
            List<Path> artifacts = SidecarArgvPolicy.collect(decision, tempDir);
            assertThat(artifacts).contains(decision.sidecarDir().resolve(SidecarArgvPolicy.BINLOG_NAME));
            assertThat(artifacts).contains(trx);
        } finally {
            SidecarArgvPolicy.cleanup(decision);
        }
    }

    @Test
    void collectDoesNotOverwriteUserBinlog() throws Exception {
        Path user = tempDir.resolve("keep.binlog");
        Files.writeString(user, "original");
        SidecarArgvPolicy.Decision decision = SidecarArgvPolicy.prepare(
            List.of("dotnet", "build", "-bl:" + user));
        try {
            assertThat(decision.injected()).isFalse();
            List<Path> artifacts = SidecarArgvPolicy.collect(decision, tempDir);
            assertThat(artifacts).contains(user);
            assertThat(Files.readString(user)).isEqualTo("original");
        } finally {
            SidecarArgvPolicy.cleanup(decision);
        }
    }

    @Test
    void msbuildPrefixIsRecognized() {
        SidecarArgvPolicy.Decision decision = SidecarArgvPolicy.prepare(List.of("msbuild", "App.sln"));
        try {
            assertThat(decision.injected()).isTrue();
            assertThat(decision.kind()).isEqualTo(SidecarArgvPolicy.Kind.BINLOG);
        } finally {
            SidecarArgvPolicy.cleanup(decision);
        }
    }
}
