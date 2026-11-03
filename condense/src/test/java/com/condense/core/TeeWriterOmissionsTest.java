package com.condense.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TeeWriterOmissionsTest {

    @TempDir
    Path tempDir;

    private Path dataDir() {
        return tempDir.resolve("data");
    }

    private TeeWriter writer(TeeMode mode) {
        IsolatedPlatformDirs dirs = new IsolatedPlatformDirs(tempDir.resolve("config"), dataDir());
        return new TeeWriter(dirs, new FixedConfig(config(mode)));
    }

    private static CondenseConfig config(TeeMode mode) {
        return new CondenseConfig(
            new CondenseConfig.HooksConfig(List.of()),
            new CondenseConfig.TeeConfig(true, mode),
            Map.of()
        );
    }

    @Test
    void omissionsModeStillSavesFailedCommands() {
        Path saved = writer(TeeMode.OMISSIONS)
            .maybeDump("git status", new ExecutionResult(128, "", "fatal", 3L), null);

        assertThat(saved).isNotNull();
        assertThat(Files.exists(saved)).isTrue();
    }

    @Test
    void omissionsModeSavesWhenOmissionMarkerPresent() {
        FilterResult filtered = new FilterResult(
            "a\n... (10 lines omitted: lines 5..14 of 24) ...\nz", 100, 20, true);

        Path saved = writer(TeeMode.OMISSIONS)
            .maybeDump("cat big.txt", new ExecutionResult(0, "raw", "", 3L), filtered);

        assertThat(saved).isNotNull();
        assertThat(Files.exists(saved)).isTrue();
    }

    @Test
    void omissionsModeSavesOnSignificantReduction() {
        FilterResult filtered = new FilterResult("compressed", 100, 40, true);

        Path saved = writer(TeeMode.OMISSIONS)
            .maybeDump("build", new ExecutionResult(0, "raw", "", 3L), filtered);

        assertThat(saved).isNotNull();
    }

    @Test
    void omissionsModeSkipsSuccessfulCommandWithNoOmissions() {
        FilterResult filtered = new FilterResult("almost raw", 100, 90, true);

        Path saved = writer(TeeMode.OMISSIONS)
            .maybeDump("build", new ExecutionResult(0, "raw", "", 3L), filtered);

        assertThat(saved).isNull();
    }

    @Test
    void omissionsModeSkipsUnfilteredSuccess() {
        Path saved = writer(TeeMode.OMISSIONS)
            .maybeDump("echo hi", new ExecutionResult(0, "hi", "", 1L), null);

        assertThat(saved).isNull();
    }

    @Test
    void failuresModeIgnoresOmissionMarkers() {
        FilterResult filtered = new FilterResult(
            "... (10 lines omitted: lines 5..14 of 24) ...", 100, 20, true);

        Path saved = writer(TeeMode.FAILURES)
            .maybeDump("cat big.txt", new ExecutionResult(0, "raw", "", 3L), filtered);

        assertThat(saved).isNull();
    }

    @Test
    void omissionMarkerDetectionCoversStageFormats() {
        assertThat(TeeWriter.hasOmissionMarker(
            "... (10 lines omitted: lines 5..14 of 24) ...")).isTrue();
        assertThat(TeeWriter.hasOmissionMarker("padding\n    ... (stack trace omitted) ...")).isTrue();
        assertThat(TeeWriter.hasOmissionMarker("... (JSON skeleton, 4096 bytes omitted) ...")).isTrue();
        assertThat(TeeWriter.hasOmissionMarker("... (0 lines omitted: showing all 2 lines) ...")).isTrue();
        assertThat(TeeWriter.hasOmissionMarker("plain output with no marker")).isFalse();
        assertThat(TeeWriter.hasOmissionMarker(null)).isFalse();
    }

    @Test
    void filterOmittedContentRequiresFiltering() {
        assertThat(TeeWriter.filterOmittedContent(null)).isFalse();
        assertThat(TeeWriter.filterOmittedContent(new FilterResult("same", 10, 10, false))).isFalse();
        assertThat(TeeWriter.filterOmittedContent(new FilterResult("same", 10, 10, true))).isFalse();
        assertThat(TeeWriter.filterOmittedContent(new FilterResult("half", 10, 5, true))).isTrue();
    }

    @Test
    void rotatesTeeFilesToTheDocumentedLimit() {
        TeeWriter writer = writer(TeeMode.FAILURES);
        for (int i = 0; i < TeeWriter.MAX_TEE_FILES + 5; i++) {
            writer.maybeDump("command-" + i,
                new ExecutionResult(1, "output " + i, "", 1L), null);
        }

        long count = teeFiles();
        assertThat(count)
            .as("rotation must keep at most MAX_TEE_FILES files")
            .isLessThanOrEqualTo(TeeWriter.MAX_TEE_FILES);
        assertThat(count).isGreaterThanOrEqualTo(TeeWriter.MAX_TEE_FILES - 5);
    }

    @Test
    void capsTeeFileAtFiveMiBWithExplicitMarker() throws Exception {
        String payload = "x".repeat(6 * 1024 * 1024);
        Path saved = writer(TeeMode.FAILURES)
            .maybeDump("huge", new ExecutionResult(1, payload, "", 1L), null);

        assertThat(saved).isNotNull();
        assertThat(Files.size(saved)).isLessThanOrEqualTo(TeeWriter.MAX_TEE_FILE_BYTES);
        assertThat(Files.readString(saved)).contains(TeeWriter.TRUNCATION_MARKER);
    }

    @Test
    void appliesOwnerOnlyPermissionsWherePosixIsAvailable() throws Exception {
        Assumptions.assumeTrue(
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
            "POSIX permissions are not available on this file system");

        Path saved = writer(TeeMode.FAILURES)
            .maybeDump("secret", new ExecutionResult(1, "", "boom", 1L), null);

        assertThat(saved).isNotNull();
        assertThat(Files.getPosixFilePermissions(saved))
            .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private long teeFiles() {
        Path teeDir = dataDir().resolve("tee");
        if (!Files.isDirectory(teeDir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(teeDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".txt")).count();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final class FixedConfig extends ConfigLoader {
        private final CondenseConfig config;

        FixedConfig(CondenseConfig config) {
            this.config = config;
        }

        @Override
        public CondenseConfig load() {
            return config;
        }
    }
}
