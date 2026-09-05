package com.condense.doctor;

import com.condense.core.PlatformDirs;
import com.condense.core.TrackingRepository;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.pipeline.config.FilterOverrideValidationResult;
import com.condense.hooks.HookInstaller;
import com.condense.hooks.HookIntegrity;
import com.condense.persist.SchemaMigrator;
import com.condense.persist.TeeRetention;
import com.condense.trust.TrustGate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Assembles a {@link DoctorReport}. Opening the analytics DB migrates and prunes;
 * that is intentional. Never proxies a command and never changes a child exit code.
 */
@ApplicationScoped
public class DoctorService {

    private final PlatformDirs platformDirs;
    private final TrackingRepository tracking;
    private final TrustGate trustGate;
    private final FilterOverrideLoader overrideLoader;
    private final HookInstaller hookInstaller;

    @Inject
    public DoctorService(
            PlatformDirs platformDirs,
            TrackingRepository tracking,
            TrustGate trustGate,
            FilterOverrideLoader overrideLoader,
            HookInstaller hookInstaller) {
        this.platformDirs = platformDirs;
        this.tracking = tracking;
        this.trustGate = trustGate;
        this.overrideLoader = overrideLoader;
        this.hookInstaller = hookInstaller;
    }

    public DoctorReport diagnose() {
        List<String> warnings = new ArrayList<>();
        Path configDir = platformDirs.resolveConfigDir();
        Path dataDir = platformDirs.resolveDataDir();
        Path database = dataDir.resolve("condense.db");
        boolean existedBefore = Files.exists(database);

        long commandCount = tracking.countAll();
        int schemaVersion = tracking.schemaVersion();
        String journalMode = tracking.journalMode();
        boolean unreadable = tracking.isDegraded() && schemaVersion < 0;
        boolean migrateFailed = tracking.isMigrateFailed();

        if (journalMode != null && !journalMode.isBlank() && !"wal".equalsIgnoreCase(journalMode)) {
            warnings.add("journal_mode is '" + journalMode + "' (expected wal)");
        }
        if (tracking.isSchemaAhead()) {
            warnings.add("schema version " + schemaVersion + " is newer than this binary (target "
                + SchemaMigrator.TARGET_VERSION + ")");
        }
        if (tracking.isDegraded() && !unreadable && !migrateFailed) {
            warnings.add("analytics writes have failed; see logs");
        }

        List<DoctorReport.HookStatus> hooks = hookStatuses(warnings);
        boolean hooksInstalled = hooks.stream().anyMatch(DoctorReport.HookStatus::installed);
        String emptyReason = emptyReason(
            unreadable, migrateFailed, existedBefore, commandCount, hooksInstalled);

        TrustInspection trust = inspectTrust(warnings);
        FilterOverrideValidationResult project = overrideLoader.validateProjectOverrides(null);
        FilterOverrideValidationResult global = overrideLoader.validateGlobalOverrides();
        warnOverride(warnings, "project", project);
        warnOverride(warnings, "global", global);

        TeeRetention.SweepResult tee = tracking.lastTeeSweep();
        int teeFiles = countTeeFiles(dataDir);
        if (tee != null && tee.remainingOld() > 0) {
            warnings.add("tee retention left " + tee.remainingOld() + " expired file(s); run doctor again");
        }

        boolean ok = !unreadable && !migrateFailed;
        return new DoctorReport(
            ok,
            schemaVersion,
            SchemaMigrator.TARGET_VERSION,
            tracking.isSchemaAhead(),
            journalMode,
            tracking.isDegraded(),
            emptyReason,
            pathString(configDir),
            pathString(dataDir),
            pathString(database),
            commandCount,
            tracking.oldestCommandTs(),
            tracking.newestCommandTs(),
            tracking.countOutcomes(),
            new LinkedHashMap<>(tracking.outcomeCountsByKind()),
            pathString(trust.path()),
            trust.entries(),
            trust.readable(),
            statusName(project),
            statusName(global),
            hooks,
            tracking.countHookEvents(),
            teeFiles,
            tee == null ? null : tee.oldestMtimeEpoch(),
            tee == null ? 0 : tee.remainingOld(),
            new ArrayList<>(warnings),
            nextStep(emptyReason)
        );
    }

    private String emptyReason(
            boolean unreadable,
            boolean migrateFailed,
            boolean existedBefore,
            long commandCount,
            boolean hooksInstalled) {
        if (unreadable) {
            return "unreadable";
        }
        if (migrateFailed) {
            return "migrate_failed";
        }
        if (tracking.isDegraded() && commandCount == 0) {
            return "degraded";
        }
        if (commandCount > 0) {
            return null;
        }
        if (!existedBefore) {
            return "no_database";
        }
        return hooksInstalled ? "zero_rows" : "hooks_absent";
    }

    private static String nextStep(String reason) {
        if (reason == null) {
            return "Analytics look healthy for this data directory.";
        }
        return switch (reason) {
            case "no_database", "hooks_absent" ->
                "Install hooks with `condense init -g`, then run a command through your agent.";
            case "zero_rows" ->
                "A proxied command has not been recorded in this data directory.";
            case "unreadable", "migrate_failed", "degraded" ->
                "Persistence failed. See logs and confirm CONDENSE_DATA_DIR points at a writable directory.";
            default -> "Run `condense doctor --format json` and inspect empty_tracking_reason.";
        };
    }

    private List<DoctorReport.HookStatus> hookStatuses(List<String> warnings) {
        List<DoctorReport.HookStatus> hooks = new ArrayList<>();
        try {
            for (HookInstaller.StatusResult status : hookInstaller.showAll()) {
                Path script = status.tool().ownedScript(HookInstaller.home());
                if (status.installed() && HookIntegrity.worldWritable(script)) {
                    warnings.add(status.tool().displayName + " hook script is world-writable");
                }
                hooks.add(new DoctorReport.HookStatus(
                    status.tool().displayName,
                    status.installed(),
                    status.integrity(),
                    status.hookFile() == null ? null : status.hookFile().toString()));
            }
        } catch (RuntimeException e) {
            hooks.add(new DoctorReport.HookStatus("unavailable", false, null, null));
        }
        return hooks;
    }

    private TrustInspection inspectTrust(List<String> warnings) {
        Path storePath = trustGate.store().storePath();
        if (storePath == null || !Files.exists(storePath)) {
            return new TrustInspection(storePath, 0, true);
        }
        try {
            int entries = trustGate.status().size();
            return new TrustInspection(storePath, entries, true);
        } catch (RuntimeException e) {
            warnings.add("trust store is unreadable: " + e.getMessage());
            return new TrustInspection(storePath, 0, false);
        }
    }

    private static void warnOverride(List<String> warnings, String label, FilterOverrideValidationResult result) {
        if (result == null) {
            return;
        }
        FilterOverrideValidationResult.Status status = result.status();
        if (status == FilterOverrideValidationResult.Status.NOT_FOUND
            || status == FilterOverrideValidationResult.Status.VALID) {
            return;
        }
        warnings.add(label + " override " + status.name().toLowerCase()
            + (result.errors().isEmpty() ? "" : ": " + result.errors().get(0)));
    }

    private static String statusName(FilterOverrideValidationResult result) {
        return result == null ? "unknown" : result.status().name().toLowerCase();
    }

    private static int countTeeFiles(Path dataDir) {
        Path tee = dataDir.resolve("tee");
        if (!Files.isDirectory(tee, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tee)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    count++;
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private static String pathString(Path path) {
        return path == null ? null : path.toAbsolutePath().toString();
    }

    private record TrustInspection(Path path, int entries, boolean readable) {}
}
