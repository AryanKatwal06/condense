package com.condense.doctor;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.Mappers;
import com.condense.core.PlatformDirs;
import com.condense.core.TrackingRepository;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.hooks.HookInstaller;
import com.condense.hooks.HookTool;
import com.condense.persist.SchemaMigrator;
import com.condense.trust.TrustGate;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DoctorServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyDataDirIsOkAndNamesAReason() throws Exception {
        Fixture fixture = fixture();
        DoctorReport report = fixture.service.diagnose();
        assertThat(report.ok()).isTrue();
        assertThat(report.schemaVersion()).isEqualTo(SchemaMigrator.TARGET_VERSION);
        assertThat(report.emptyTrackingReason()).isIn("no_database", "zero_rows", "hooks_absent");
        assertThat(report.nextStep()).isNotBlank();

        JsonNode json = Mappers.JSON.readTree(Mappers.JSON.writeValueAsString(report));
        assertThat(json.has("empty_tracking_reason")).isTrue();
        assertThat(json.has("schema_version")).isTrue();
        assertThat(json.has("next_step")).isTrue();

        DoctorCommand command = new DoctorCommand(fixture.service);
        assertThat(command.call()).isZero();
        fixture.tracking.close();
    }

    @Test
    void healthyDatabaseHasNullEmptyReason() throws Exception {
        Fixture fixture = fixture();
        fixture.tracking.insert("git status", "abc", "/tmp", 10, 2, 1L);
        DoctorReport report = fixture.service.diagnose();
        assertThat(report.ok()).isTrue();
        assertThat(report.emptyTrackingReason()).isNull();
        assertThat(report.commandCount()).isEqualTo(1);
        assertThat(report.schemaVersion()).isEqualTo(1);
        assertThat(new DoctorCommand(fixture.service).call()).isZero();
        fixture.tracking.close();
    }

    @Test
    void unreadableDatabaseExitsOne() throws Exception {
        Path config = tempDir.resolve("bad-config");
        Path data = tempDir.resolve("bad-data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("condense.db"), "not sqlite");
        PlatformDirs dirs = new IsolatedPlatformDirs(config, data);
        TrackingRepository tracking = new TrackingRepository(dirs);
        DoctorService service = new DoctorService(
            dirs,
            tracking,
            new TrustGate(dirs),
            new FilterOverrideLoader(dirs),
            noHooks()
        );
        DoctorReport report = service.diagnose();
        assertThat(report.ok()).isFalse();
        assertThat(report.emptyTrackingReason()).isIn("unreadable", "migrate_failed", "degraded");
        assertThat(new DoctorCommand(service).call()).isEqualTo(1);
        tracking.close();
    }

    private Fixture fixture() {
        PlatformDirs dirs = new IsolatedPlatformDirs(tempDir.resolve("config"), tempDir.resolve("data"));
        TrackingRepository tracking = new TrackingRepository(dirs);
        DoctorService service = new DoctorService(
            dirs,
            tracking,
            new TrustGate(dirs),
            new FilterOverrideLoader(dirs),
            noHooks()
        );
        return new Fixture(tracking, service);
    }

    private static HookInstaller noHooks() {
        return new HookInstaller() {
            @Override
            public List<StatusResult> showAll() {
                return List.of(new StatusResult(HookTool.CURSOR, false, Path.of("/tmp/none")));
            }
        };
    }

    private record Fixture(TrackingRepository tracking, DoctorService service) {}
}
