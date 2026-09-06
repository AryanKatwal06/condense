package com.condense.persist;

import com.condense.core.Mappers;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Restart-visible count of analytics write losses. Lives beside the database
 * so a {@code SQLITE_READONLY} ledger can still report that rows were dropped.
 * Schema version stays 2. Unreadable JSON keeps the last-good count for that
 * file in this JVM and does not reset to 0.
 */
public final class WriteFailureLedger {

    public static final String FILE_NAME = "write-failures.json";

    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<String, Snapshot> LAST_GOOD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> UNWRITABLE = new ConcurrentHashMap<>();
    private static AtomicFile atomic = AtomicFile.SYSTEM;

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

    public static boolean isUnwritable(Path dataDir) {
        Path file = file(dataDir);
        return file != null && UNWRITABLE.containsKey(key(file));
    }

    public static String unwritableError(Path dataDir) {
        Path file = file(dataDir);
        return file == null ? null : UNWRITABLE.get(key(file));
    }

    static void useAtomicFile(AtomicFile file) {
        atomic = file == null ? AtomicFile.SYSTEM : file;
    }

    static void restore() {
        atomic = AtomicFile.SYSTEM;
        LAST_GOOD.clear();
        UNWRITABLE.clear();
    }

    public static boolean jsonUnreadable(Path dataDir) {
        Path file = file(dataDir);
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            Mappers.JSON.readValue(Files.readString(file), Snapshot.class);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public static Snapshot read(Path dataDir) {
        Path file = file(dataDir);
        synchronized (LOCK) {
            return readFile(file);
        }
    }

    public static void record(Path dataDir, String error) {
        if (dataDir == null) {
            return;
        }
        Path file = file(dataDir);
        try {
            synchronized (LOCK) {
                Snapshot current = readFile(file);
                Snapshot next = new Snapshot(
                    current.count() + 1,
                    error == null || error.isBlank() ? current.lastError() : error);
                atomic.write(
                    file,
                    dataDir,
                    Mappers.JSON.writeValueAsString(next).getBytes(StandardCharsets.UTF_8),
                    ".condense-ledger-",
                    ".tmp");
                remember(file, next);
                UNWRITABLE.remove(key(file));
            }
        } catch (Exception e) {
            if (file != null) {
                UNWRITABLE.put(key(file), e.getMessage() == null ? "" : e.getMessage());
            }
            // Fail-open. Loss of the ledger must not change a child exit code.
        }
    }

    private static Snapshot readFile(Path file) {
        Snapshot remembered = file == null ? Snapshot.empty() : LAST_GOOD.getOrDefault(key(file), Snapshot.empty());
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return remembered;
            }
            Snapshot snapshot = Mappers.JSON.readValue(Files.readString(file), Snapshot.class);
            if (snapshot == null) {
                return remembered;
            }
            remember(file, snapshot);
            return snapshot;
        } catch (Exception e) {
            return remembered;
        }
    }

    private static void remember(Path file, Snapshot snapshot) {
        if (file != null && snapshot != null) {
            LAST_GOOD.put(key(file), snapshot);
        }
    }

    private static String key(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }
}
