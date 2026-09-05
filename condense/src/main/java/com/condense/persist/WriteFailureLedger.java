package com.condense.persist;

import com.condense.core.Mappers;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Restart-visible count of analytics write losses. Lives beside the database
 * so a {@code SQLITE_READONLY} ledger can still report that rows were dropped.
 * Schema version stays 2.
 */
public final class WriteFailureLedger {

    public static final String FILE_NAME = "write-failures.json";

    private static final Object LOCK = new Object();

    private WriteFailureLedger() {}

    @RegisterForReflection
    public record Snapshot(
        @JsonProperty("count") long count,
        @JsonProperty("last_error") String lastError
    ) {
        public static Snapshot empty() {
            return new Snapshot(0, null);
        }
    }

    public static Path file(Path dataDir) {
        return dataDir == null ? null : dataDir.resolve(FILE_NAME);
    }

    public static Snapshot read(Path dataDir) {
        Path file = file(dataDir);
        if (file == null || !Files.isRegularFile(file)) {
            return Snapshot.empty();
        }
        synchronized (LOCK) {
            return readFile(file);
        }
    }

    public static void record(Path dataDir, String error) {
        if (dataDir == null) {
            return;
        }
        try {
            Files.createDirectories(dataDir);
            Path file = file(dataDir);
            synchronized (LOCK) {
                Snapshot current = readFile(file);
                Snapshot next = new Snapshot(
                    current.count() + 1,
                    error == null || error.isBlank() ? current.lastError() : error);
                Files.writeString(file, Mappers.JSON.writeValueAsString(next));
            }
        } catch (Exception ignored) {
            // Fail-open. Loss of the ledger must not change a child exit code.
        }
    }

    private static Snapshot readFile(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return Snapshot.empty();
            }
            Snapshot snapshot = Mappers.JSON.readValue(Files.readString(file), Snapshot.class);
            return snapshot == null ? Snapshot.empty() : snapshot;
        } catch (Exception e) {
            return Snapshot.empty();
        }
    }
}
