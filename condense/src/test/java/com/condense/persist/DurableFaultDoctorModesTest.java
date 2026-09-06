package com.condense.persist;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.Mappers;
import com.condense.core.PlatformDirs;
import com.condense.core.TrackingRepository;
import com.condense.doctor.DoctorReport;
import com.condense.doctor.DoctorService;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.hooks.HookInstaller;
import com.condense.hooks.HookTool;
import com.condense.trust.TrustGate;
import jakarta.enterprise.inject.Vetoed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DurableFaultDoctorModesTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void restore() {
        WriteFailureLedger.restore();
        CondenseClock.restore();
    }

    @Test
    void everyCatalogIdAppearsInDoctorOutput() throws Exception {
        DurableFaultCatalogTest.Catalog catalog;
        try (InputStream in = DurableFaultCatalogTest.class.getResourceAsStream(
                "/reliability/durable-fault-contract.json")) {
            catalog = Mappers.JSON.readValue(in, DurableFaultCatalogTest.Catalog.class);
        }

        StringBuilder haystack = new StringBuilder();
        haystack.append(diagnoseOrphansAndLedger());
        haystack.append(diagnoseSchemaAhead());
        haystack.append(diagnoseCorrupt());
        haystack.append(diagnoseClockJump());
        haystack.append(diagnoseOverride());
        haystack.append(diagnoseIntegrity());
        String text = haystack.toString();

        Set<String> skipped = new HashSet<>();
        skipped.add("doctor_modes");
        skipped.add("override_cache_appear");
        skipped.add("hash_mismatch");
        skipped.add("pipeline_build_failed");
        skipped.add("clock_jump_back");
        for (DurableFaultCatalogTest.Entry entry : catalog.entries()) {
            if (entry.nativeIt() || skipped.contains(entry.id()) || entry.id().startsWith("proxy_")) {
                continue;
            }
            assertThat(text)
                .as("doctor JSON must name catalog id %s", entry.id())
                .contains(entry.id());
        }
    }

    private String diagnoseOrphansAndLedger() throws Exception {
        Path config = tempDir.resolve("o-config");
        Path data = tempDir.resolve("o-data");
        Files.createDirectories(config);
        Files.createDirectories(data);
        Files.writeString(config.resolve("trust.json.tmp"), "partial");
        Files.writeString(config.resolve(".condense-config-x.toml.tmp"), "partial");
        Files.writeString(data.resolve(".condense-hook-y.tmp"), "partial");
        WriteFailureLedger.record(data, "disk_full rename_fail SQLITE_BUSY readonly symlink_swap");
        Files.writeString(WriteFailureLedger.file(data), "not-json{");
        WriteFailureLedger.useAtomicFile(new AtomicFile(FaultyDurableIo.readOnly()));
        WriteFailureLedger.record(data, "readonly");
        return json(config, data);
    }

    private String diagnoseSchemaAhead() throws Exception {
        Path config = tempDir.resolve("ahead-config");
        Path data = tempDir.resolve("ahead-data");
        Files.createDirectories(config);
        Files.createDirectories(data);
        IsolatedPlatformDirs dirs = new IsolatedPlatformDirs(config, data);
        TrackingRepository seed = new TrackingRepository(dirs);
        try {
            seed.insert("keep", "p", "/tmp", 1, 1, 1L);
        } finally {
            seed.close();
        }
        Path db = data.resolve("condense.db");
        Driver driver = new org.sqlite.JDBC();
        try (Connection connection = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = connection.createStatement()) {
            st.executeUpdate("PRAGMA user_version = 99");
        }
        return json(config, data);
    }

    private String diagnoseCorrupt() throws Exception {
        Path config = tempDir.resolve("c-config");
        Path data = tempDir.resolve("c-data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("condense.db"), "not a sqlite database");
        return json(config, data);
    }

    private String diagnoseClockJump() throws Exception {
        Path config = tempDir.resolve("clk-config");
        Path data = tempDir.resolve("clk-data");
        IsolatedPlatformDirs dirs = new IsolatedPlatformDirs(config, data);
        TrackingRepository repo = new TrackingRepository(dirs);
        try {
            repo.insertAt(CondenseClock.epochSeconds() + (40L * 86400L), "future", "p", "/tmp", 1, 1, 1L);
        } finally {
            repo.close();
        }
        return json(config, data);
    }

    private String diagnoseIntegrity() throws Exception {
        Path config = tempDir.resolve("int-config");
        Path data = tempDir.resolve("int-data");
        IsolatedPlatformDirs dirs = new IsolatedPlatformDirs(config, data);
        TrackingRepository seed = new TrackingRepository(dirs);
        try {
            seed.insert("keep", "p", "/tmp", 1, 1, 1L);
        } finally {
            seed.close();
        }
        Path db = data.resolve("condense.db");
        byte[] bytes = Files.readAllBytes(db);
        if (bytes.length > 200) {
            bytes[180] ^= (byte) 0xFF;
            Files.write(db, bytes);
        }
        return json(config, data);
    }

    private String diagnoseOverride() throws Exception {
        Path config = tempDir.resolve("ov-config");
        Path data = tempDir.resolve("ov-data");
        Files.createDirectories(config);
        Files.writeString(config.resolve("filters.toml"), """
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """);
        return json(config, data);
    }

    private String json(Path config, Path data) throws Exception {
        PlatformDirs dirs = new IsolatedPlatformDirs(config, data);
        TrackingRepository tracking = new TrackingRepository(dirs);
        try {
            DoctorService service = new DoctorService(
                dirs, tracking, new TrustGate(dirs), new FilterOverrideLoader(dirs), new NoHooks());
            DoctorReport report = service.diagnose();
            return Mappers.JSON.writeValueAsString(report);
        } finally {
            tracking.close();
        }
    }

    @Vetoed
    private static final class NoHooks extends HookInstaller {
        @Override
        public List<HookInstaller.StatusResult> showAll() {
            return List.of(new StatusResult(HookTool.CURSOR, false, Path.of("/tmp/none")));
        }
    }
}
