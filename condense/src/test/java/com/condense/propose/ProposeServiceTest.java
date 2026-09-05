package com.condense.propose;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.ProjectFingerprint;
import com.condense.core.TrackingRepository;
import com.condense.filter.pipeline.FilterIncident;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProposeServiceTest {

    @TempDir
    Path tempDir;

    private TrackingRepository tracking;
    private ProposeService service;
    private Path work;

    @BeforeEach
    void setUp() throws Exception {
        tracking = new TrackingRepository(new IsolatedPlatformDirs(
            tempDir.resolve("config"),
            tempDir.resolve("data")));
        service = new ProposeService();
        work = Files.createDirectories(tempDir.resolve("proj"));
        Files.createDirectories(work.resolve(".git"));
        Files.createDirectories(work.resolve("prisma"));
        Files.writeString(work.resolve("pnpm-lock.yaml"), "lockfileVersion: '9.0'\n");
        Files.writeString(work.resolve("prisma").resolve("schema.prisma"), "generator client {\n}\n");
    }

    @AfterEach
    void tearDown() {
        tracking.close();
    }

    @Test
    void coveragePinsPnpmAndPrismaAndIsReproducible() {
        ProposeReport first = service.propose(work, work, tracking);
        ProposeReport second = service.propose(work, work, tracking);
        assertThat(first.failed()).isFalse();
        assertThat(first.discoverRecommend()).contains("pnpm-install", "prisma");
        assertThat(readyCommands(first, ProposeReport.KIND_COVERAGE))
            .contains("pnpm install", "prisma migrate");
        ProposeReport.Proposal pnpm = ready(first, "pnpm install");
        assertThat(pnpm.status()).isEqualTo(ProposeReport.STATUS_READY);
        assertThat(pnpm.toml()).contains("strategy = \"ansi_strip\"");
        assertThat(pnpm.requiredCapability()).isEqualTo("reshape");
        assertThat(second.proposals().stream().map(ProposeReport.Proposal::id).toList())
            .isEqualTo(first.proposals().stream().map(ProposeReport.Proposal::id).toList());
        assertThat(second.proposals().getFirst().toml()).isEqualTo(first.proposals().getFirst().toml());
    }

    @Test
    void curlStyleGateIsNotRepresentableWhenRecommendedDirectly() {
        ProposeReport.Proposal blocked = invokeCoverage("curl", "curl");
        assertThat(blocked.status()).isEqualTo(ProposeReport.STATUS_BLOCKED_NOT_REPRESENTABLE);
        assertThat(blocked.toml()).isEmpty();
    }

    @Test
    void unmatchedHighVolumeCommandIsReadyReduceOnly() throws Exception {
        Path root = work.toRealPath();
        String cwd = root.toString();
        String project = ProjectFingerprint.of(cwd);
        for (int i = 0; i < 5; i++) {
            tracking.insert("weirdtool build --flag", project, cwd, 500, 500, 1L);
        }
        ProposeReport report = service.propose(work, work, tracking);
        ProposeReport.Proposal unmatched = ready(report, "weirdtool");
        assertThat(unmatched.kind()).isEqualTo(ProposeReport.KIND_UNMATCHED);
        assertThat(unmatched.status()).isEqualTo(ProposeReport.STATUS_READY);
        assertThat(unmatched.requiredCapability()).isEqualTo("reduce");
        assertThat(unmatched.toml()).contains("tail_lines");
        assertThat(unmatched.id()).isEqualTo(ProposeService.proposalId(
            ProposeReport.KIND_UNMATCHED, "weirdtool", unmatched.toml()));
    }

    @Test
    void gitFamilyIsNotProposedAsUnmatched() throws Exception {
        Path root = work.toRealPath();
        String cwd = root.toString();
        String project = ProjectFingerprint.of(cwd);
        for (int i = 0; i < 5; i++) {
            tracking.insert("git mystery-subcommand", project, cwd, 500, 500, 1L);
        }
        ProposeReport report = service.propose(work, work, tracking);
        assertThat(report.proposals())
            .noneMatch(p -> ProposeReport.KIND_UNMATCHED.equals(p.kind())
                && "git".equals(p.command()));
    }

    @Test
    void safetyIdentityAfterEnoughIncidents() throws Exception {
        Path root = work.toRealPath();
        String cwd = root.toString();
        String project = ProjectFingerprint.of(cwd);
        tracking.insert("pnpm install", project, cwd, 100, 20, 1L);
        for (int i = 0; i < 3; i++) {
            tracking.insertOutcome("pnpm install", project,
                FilterIncident.stageException("grouping", "boom"));
        }
        ProposeReport report = service.propose(work, work, tracking);
        // coverage claims pnpm install first; safety should not clobber it
        assertThat(report.proposals())
            .noneMatch(p -> ProposeReport.KIND_SAFETY.equals(p.kind())
                && "pnpm install".equals(p.command())
                && ProposeReport.STATUS_READY.equals(p.status()));

        for (int i = 0; i < 3; i++) {
            tracking.insertOutcome("custom-lint", project,
                FilterIncident.applyFallback("custom-lint", "fallback"));
        }
        ProposeReport again = service.propose(work, work, tracking);
        ProposeReport.Proposal safety = ready(again, "custom-lint");
        assertThat(safety.kind()).isEqualTo(ProposeReport.KIND_SAFETY);
        assertThat(safety.toml()).contains("stages = []");
        assertThat(safety.requiredCapability()).isEqualTo("reduce");
    }

    @Test
    void existingOverrideIsSkippedNotClobbered() throws Exception {
        Path condense = Files.createDirectories(work.resolve(".condense"));
        Files.writeString(condense.resolve("filters.toml"), """
            schema_version = 1
            [filters."pnpm install"]
            stages = []
            """);
        ProposeReport report = service.propose(work, work, tracking);
        assertThat(report.proposals())
            .anyMatch(p -> "pnpm install".equals(p.command())
                && ProposeReport.STATUS_SKIPPED_EXISTING.equals(p.status()));
        assertThat(report.proposals())
            .noneMatch(p -> "pnpm install".equals(p.command())
                && ProposeReport.STATUS_READY.equals(p.status()));
    }

    @Test
    void missingDatabaseDoesNotCreateOne() {
        tracking.close();
        ProposeService noDb = new ProposeService();
        ProposeReport report = noDb.propose(work, work, new TrackingRepository(
            new IsolatedPlatformDirs(tempDir.resolve("cfg2"), tempDir.resolve("data2"))));
        assertThat(report.failed()).isFalse();
        assertThat(report.analyticsUnavailable()).isTrue();
        assertThat(tempDir.resolve("data2").resolve("condense.db")).doesNotExist();
        assertThat(readyCommands(report, ProposeReport.KIND_COVERAGE)).isNotEmpty();
    }

    @Test
    void belowIncidentThresholdEmitsNoSafety() throws Exception {
        Path root = work.toRealPath();
        String cwd = root.toString();
        String project = ProjectFingerprint.of(cwd);
        tracking.insertOutcome("custom-lint", project,
            FilterIncident.stageException("x", "once"));
        tracking.insertOutcome("custom-lint", project,
            FilterIncident.stageException("x", "twice"));
        ProposeReport report = service.propose(work, work, tracking);
        assertThat(report.proposals())
            .noneMatch(p -> "custom-lint".equals(p.command()));
    }

    private static ProposeReport.Proposal invokeCoverage(String definition, String command) {
        ProposeService local = new ProposeService();
        return local.coverageForTest(definition, command);
    }

    private static List<String> readyCommands(ProposeReport report, String kind) {
        return report.proposals().stream()
            .filter(p -> kind.equals(p.kind()) && ProposeReport.STATUS_READY.equals(p.status()))
            .map(ProposeReport.Proposal::command)
            .toList();
    }

    private static ProposeReport.Proposal ready(ProposeReport report, String command) {
        return report.proposals().stream()
            .filter(p -> command.equals(p.command()) && ProposeReport.STATUS_READY.equals(p.status()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no ready proposal for " + command + " in " + report.proposals()));
    }
}
