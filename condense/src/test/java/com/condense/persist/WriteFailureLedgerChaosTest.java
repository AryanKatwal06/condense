package com.condense.persist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class WriteFailureLedgerChaosTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void restore() {
        WriteFailureLedger.restore();
    }

    @Test
    void corruptJsonDoesNotResetCountToZero() throws Exception {
        Path data = tempDir.resolve("ledger-data");
        Files.createDirectories(data);
        WriteFailureLedger.record(data, "first");
        assertThat(WriteFailureLedger.read(data).count()).isEqualTo(1);

        Files.writeString(WriteFailureLedger.file(data), "not-json{");
        WriteFailureLedger.Snapshot snapshot = WriteFailureLedger.read(data);
        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.lastError()).isEqualTo("first");
    }

    @Test
    void unwritableDataDirDoesNotThrow() throws Exception {
        Path data = tempDir.resolve("ro-data");
        Files.createDirectories(data);
        WriteFailureLedger.record(data, "seed");
        WriteFailureLedger.useAtomicFile(new AtomicFile(FaultyDurableIo.readOnly()));
        assertThatCode(() -> WriteFailureLedger.record(data, "readonly"))
            .doesNotThrowAnyException();
        assertThat(WriteFailureLedger.isUnwritable(data)).isTrue();
        assertThat(WriteFailureLedger.unwritableError(data)).contains("readonly");
        assertThat(WriteFailureLedger.read(data).count()).isEqualTo(1);
    }
}
