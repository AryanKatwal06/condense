package com.condense.persist;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.PlatformDirs;
import com.condense.core.TrackingRepository;
import com.condense.doctor.DoctorReport;
import com.condense.doctor.DoctorService;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.hooks.HookInstaller;
import com.condense.hooks.HookTool;
import com.condense.trust.TrustGate;
import jakarta.enterprise.inject.Vetoed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void fiveRepositoriesShareOneFileWithoutCorruption() throws Exception {
        PlatformDirs dirs = new IsolatedPlatformDirs(tempDir.resolve("config"), tempDir.resolve("data"));
        int repos = 5;
        int insertsEach = 40;
        ExecutorService pool = Executors.newFixedThreadPool(repos);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < repos; i++) {
            final int repoId = i;
            tasks.add(() -> {
                TrackingRepository repo = new TrackingRepository(dirs);
                try {
                    for (int n = 0; n < insertsEach; n++) {
                        repo.insert("cmd-" + repoId + "-" + n, "proj", "/tmp", 10, 2, 1L);
                    }
                    return insertsEach;
                } finally {
                    repo.close();
                }
            });
        }

        int written = 0;
        for (Future<Integer> future : pool.invokeAll(tasks)) {
            written += future.get();
        }
        pool.shutdownNow();
        assertThat(written).isEqualTo(repos * insertsEach);

        TrackingRepository check = new TrackingRepository(dirs);
        try {
            long expected = (long) repos * insertsEach;
            long stored = check.countAll();
            WriteFailureLedger.Snapshot losses = WriteFailureLedger.read(tempDir.resolve("data"));
            assertThat(stored + losses.count()).isEqualTo(expected);
            if (stored < expected) {
                assertThat(losses.count()).isGreaterThanOrEqualTo(1);
                assertThat(losses.lastError()).isNotBlank();
            }

            DoctorService doctor = new DoctorService(
                dirs,
                check,
                new TrustGate(dirs),
                new FilterOverrideLoader(dirs),
                new NoHooks());
            DoctorReport report = doctor.diagnose();
            assertThat(report.persistenceWriteFailures()).isEqualTo(losses.count());
            if (stored < expected) {
                assertThat(report.persistenceWriteFailures()).isGreaterThanOrEqualTo(1);
                assertThat(report.persistenceWriteLastError()).isNotBlank();
            }
        } finally {
            check.close();
        }

        Path db = tempDir.resolve("data").resolve("condense.db");
        Driver driver = new org.sqlite.JDBC();
        try (Connection connection = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("ok");
        }
    }

    @Vetoed
    private static final class NoHooks extends HookInstaller {
        @Override
        public List<HookInstaller.StatusResult> showAll() {
            return List.of(new HookInstaller.StatusResult(HookTool.CURSOR, false, Path.of("/tmp/none")));
        }
    }
}
