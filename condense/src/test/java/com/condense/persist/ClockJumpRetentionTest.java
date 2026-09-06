package com.condense.persist;

import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.TrackingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ClockJumpRetentionTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreClock() {
        CondenseClock.restore();
    }

    @Test
    void jumpForwardDeletesExpiredRows() {
        Instant freeze = Instant.parse("2020-01-01T00:00:00Z");
        CondenseClock.install(Clock.fixed(freeze, ZoneOffset.UTC));
        Path data = tempDir.resolve("fwd");
        TrackingRepository repo = new TrackingRepository(
            new IsolatedPlatformDirs(tempDir.resolve("cfg"), data));
        try {
            long now = CondenseClock.epochSeconds();
            repo.insertAt(now - (70L * 86400L), "old", "proj", "/tmp", 10, 2, 1L);
            repo.insertAt(now, "current", "proj", "/tmp", 10, 2, 1L);
            assertThat(repo.countAll()).isEqualTo(2);
        } finally {
            repo.close();
        }

        CondenseClock.install(Clock.fixed(freeze.plusSeconds(30L * 86400L), ZoneOffset.UTC));
        TrackingRepository after = new TrackingRepository(
            new IsolatedPlatformDirs(tempDir.resolve("cfg"), data));
        try {
            assertThat(after.countAll()).isEqualTo(1);
            assertThat(after.queryRecent(10, null).get(0).command()).isEqualTo("current");
        } finally {
            after.close();
        }
    }

    @Test
    void jumpBackDoesNotWipeCurrentRows() {
        Instant freeze = Instant.parse("2020-06-01T00:00:00Z");
        CondenseClock.install(Clock.fixed(freeze, ZoneOffset.UTC));
        Path data = tempDir.resolve("back");
        TrackingRepository repo = new TrackingRepository(
            new IsolatedPlatformDirs(tempDir.resolve("cfg2"), data));
        try {
            repo.insert("current", "proj", "/tmp", 10, 2, 1L);
            assertThat(repo.countAll()).isEqualTo(1);
        } finally {
            repo.close();
        }

        CondenseClock.install(Clock.fixed(freeze.minusSeconds(30L * 86400L), ZoneOffset.UTC));
        TrackingRepository after = new TrackingRepository(
            new IsolatedPlatformDirs(tempDir.resolve("cfg2"), data));
        try {
            assertThat(after.countAll()).isEqualTo(1);
            assertThat(after.queryRecent(10, null).get(0).command()).isEqualTo("current");
        } finally {
            after.close();
        }
    }
}
