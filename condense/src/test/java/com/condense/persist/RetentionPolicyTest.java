package com.condense.persist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void cutoffIsNinetyDays() {
        long now = 2_000_000_000L;
        assertThat(RetentionPolicy.cutoffEpochSeconds(now))
            .isEqualTo(now - 90L * 86400L);
    }

    @Test
    void teeSweepDeletesOldRegularFilesAndKeepsRecent() throws Exception {
        Path data = tempDir.resolve("data");
        Path tee = data.resolve("tee");
        Files.createDirectories(tee);
        Path oldFile = tee.resolve("aaa11111-1.txt");
        Path recentFile = tee.resolve("bbb22222-2.txt");
        Files.writeString(oldFile, "old");
        Files.writeString(recentFile, "new");
        Instant old = Instant.now().minusSeconds((RetentionPolicy.RETENTION_DAYS + 5L) * 86400L);
        Files.setLastModifiedTime(oldFile, FileTime.from(old));
        Files.setLastModifiedTime(recentFile, FileTime.from(Instant.now()));

        TeeRetention.SweepResult result = TeeRetention.prune(data);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(Files.exists(oldFile)).isFalse();
        assertThat(Files.exists(recentFile)).isTrue();
        assertThat(result.remainingOld()).isZero();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void teeSweepDoesNotFollowSymlinks() throws Exception {
        Path data = tempDir.resolve("data");
        Path tee = data.resolve("tee");
        Files.createDirectories(tee);
        Path outside = tempDir.resolve("secret.txt");
        Files.writeString(outside, "secret");
        Path link = tee.resolve("link.txt");
        Files.createSymbolicLink(link, outside);
        Files.setLastModifiedTime(link, FileTime.from(
            Instant.now().minusSeconds((RetentionPolicy.RETENTION_DAYS + 5L) * 86400L)));

        TeeRetention.SweepResult result = TeeRetention.prune(data);
        assertThat(result.deleted()).isZero();
        assertThat(Files.exists(outside)).isTrue();
        assertThat(Files.exists(link)).isTrue();
    }

    @Test
    void teeSweepRefusesPathOutsideTee() throws Exception {
        Path data = tempDir.resolve("data");
        Files.createDirectories(data);
        Path outsider = tempDir.resolve("outside.txt");
        Files.writeString(outsider, "nope");
        Files.setLastModifiedTime(outsider, FileTime.from(
            Instant.now().minusSeconds((RetentionPolicy.RETENTION_DAYS + 5L) * 86400L)));

        TeeRetention.SweepResult result = TeeRetention.prune(data);
        assertThat(result.deleted()).isZero();
        assertThat(Files.exists(outsider)).isTrue();
    }

    @Test
    void teeSweepHonorsDeletionCap() throws Exception {
        Path data = tempDir.resolve("data");
        Path tee = data.resolve("tee");
        Files.createDirectories(tee);
        Instant old = Instant.now().minusSeconds((RetentionPolicy.RETENTION_DAYS + 5L) * 86400L);
        int extra = 3;
        for (int i = 0; i < RetentionPolicy.TEE_SWEEP_LIMIT + extra; i++) {
            Path file = tee.resolve(String.format("%08x-old.txt", i));
            Files.writeString(file, "x");
            Files.setLastModifiedTime(file, FileTime.from(old));
        }

        TeeRetention.SweepResult result = TeeRetention.prune(data);
        assertThat(result.deleted()).isEqualTo(RetentionPolicy.TEE_SWEEP_LIMIT);
        assertThat(result.remainingOld()).isEqualTo(extra);
    }
}
