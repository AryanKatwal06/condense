package com.condense.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SafePathValidatorTest {

    @TempDir
    Path tempDir;

    private Path fakeConfigDir;
    private Path fakeDataDir;
    private PlatformDirs platformDirs;
    private SafePathValidator validator;

    @BeforeEach
    void setUp() throws IOException {
        fakeConfigDir = tempDir.resolve("config/condense");
        fakeDataDir = tempDir.resolve("data/condense");
        Files.createDirectories(fakeConfigDir);
        Files.createDirectories(fakeDataDir);

        platformDirs = new PlatformDirs() {
            @Override
            public Path resolveConfigDir() {
                return fakeConfigDir;
            }

            @Override
            public Path resolveDataDir() {
                return fakeDataDir;
            }

            @Override
            public Path getConfigDir() {
                return fakeConfigDir;
            }

            @Override
            public Path getDataDir() {
                return fakeDataDir;
            }
        };

        validator = new SafePathValidator(platformDirs);
    }

    @Test
    void isInsideCondenseOwnedLocation_acceptsValidSubpaths() {
        Path db = fakeDataDir.resolve("condense.db");
        Path config = fakeConfigDir.resolve("config.toml");
        Path nested = fakeDataDir.resolve("sub").resolve("file.txt");

        assertThat(validator.isInsideCondenseOwnedLocation(db)).isTrue();
        assertThat(validator.isInsideCondenseOwnedLocation(config)).isTrue();
        assertThat(validator.isInsideCondenseOwnedLocation(nested)).isTrue();
        assertThat(validator.isInsideCondenseOwnedLocation(fakeDataDir)).isTrue();
        assertThat(validator.isInsideCondenseOwnedLocation(fakeConfigDir)).isTrue();
    }

    @Test
    void isInsideCondenseOwnedLocation_rejectsOutsidePaths() {
        Path outside = tempDir.resolve("outside.txt");
        Path root = Path.of(System.getProperty("user.home"));

        assertThat(validator.isInsideCondenseOwnedLocation(outside)).isFalse();
        assertThat(validator.isInsideCondenseOwnedLocation(root)).isFalse();
        assertThat(validator.isInsideCondenseOwnedLocation(null)).isFalse();
    }

    @Test
    void validateFileTarget_acceptsValidFiles() throws IOException {
        Path dbFile = fakeDataDir.resolve("condense.db");
        Files.writeString(dbFile, "sqlite-data");

        SafePathValidator.ValidationResult result = validator.validateFileTarget(dbFile);
        assertThat(result.isSafe()).isTrue();
        assertThat(result.status()).isEqualTo(SafePathValidator.Status.SAFE);
    }

    @Test
    void validateFileTarget_rejectsNonExistentFiles() {
        Path missing = fakeDataDir.resolve("non-existent.db");

        SafePathValidator.ValidationResult result = validator.validateFileTarget(missing);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.status()).isEqualTo(SafePathValidator.Status.NOT_FOUND);
    }

    @Test
    void validateFileTarget_rejectsOutsidePath() throws IOException {
        Path outsideFile = tempDir.resolve("critical-user-file.txt");
        Files.writeString(outsideFile, "secret");

        SafePathValidator.ValidationResult result = validator.validateFileTarget(outsideFile);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.status()).isEqualTo(SafePathValidator.Status.OUTSIDE_ALLOWED_LOCATION);
    }

    @Test
    void validateFileTarget_rejectsSymlinkPointingOutside() throws IOException {
        Path outsideTarget = tempDir.resolve("host-file.txt");
        Files.writeString(outsideTarget, "system-data");

        Path symlinkInCondense = fakeDataDir.resolve("condense.db");
        try {
            Files.createSymbolicLink(symlinkInCondense, outsideTarget);
        } catch (UnsupportedOperationException | SecurityException | IOException e) {
            // Some Windows environments without Developer Mode may restrict symlink creation
            return;
        }

        SafePathValidator.ValidationResult result = validator.validateFileTarget(symlinkInCondense);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.status()).isEqualTo(SafePathValidator.Status.SYMLINK_ESCAPE);
        assertThat(result.reason()).contains("Safety refusal");
    }

    @Test
    void validateDirectoryForDeletion_acceptsCleanDirectoryWithKnownFiles() throws IOException {
        Files.writeString(fakeDataDir.resolve("condense.db"), "data");
        Files.writeString(fakeDataDir.resolve("condense.db-wal"), "wal");
        Files.writeString(fakeDataDir.resolve(".install_dir"), "/usr/local/bin");

        SafePathValidator.ValidationResult result = validator.validateDirectoryForDeletion(fakeDataDir);
        assertThat(result.isSafe()).isTrue();
        assertThat(result.status()).isEqualTo(SafePathValidator.Status.SAFE);
    }

    @Test
    void validateDirectoryForDeletion_rejectsDirectoryWithUnexpectedFiles() throws IOException {
        Files.writeString(fakeDataDir.resolve("condense.db"), "data");
        Files.writeString(fakeDataDir.resolve("important-user-document.pdf"), "content");

        SafePathValidator.ValidationResult result = validator.validateDirectoryForDeletion(fakeDataDir);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.status()).isEqualTo(SafePathValidator.Status.UNEXPECTED_CONTENTS);
        assertThat(result.unexpectedEntries()).hasSize(1);
        assertThat(result.unexpectedEntries().get(0).getFileName().toString()).isEqualTo("important-user-document.pdf");
    }

    @Test
    void validateDirectoryForDeletion_rejectsDirectoryWithSubdirectories() throws IOException {
        Path sub = fakeDataDir.resolve("unexpected-folder");
        Files.createDirectories(sub);

        SafePathValidator.ValidationResult result = validator.validateDirectoryForDeletion(fakeDataDir);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.status()).isEqualTo(SafePathValidator.Status.UNEXPECTED_CONTENTS);
        assertThat(result.unexpectedEntries()).contains(sub);
    }

    @Test
    void validateDirectoryForDeletion_rejectsOutsideDirectory() {
        SafePathValidator.ValidationResult result = validator.validateDirectoryForDeletion(tempDir);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.status()).isEqualTo(SafePathValidator.Status.OUTSIDE_ALLOWED_LOCATION);
    }
}
