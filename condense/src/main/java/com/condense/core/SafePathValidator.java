package com.condense.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Validates file and directory paths before deletion to ensure condense only
 * modifies or removes files strictly within its own runtime-resolved directories.
 *
 * <p>The allowlist of safe directories is always dynamically derived at runtime
 * from {@link PlatformDirs}, preventing hardcoded path drift across platforms
 * or custom XDG environments.
 */
public class SafePathValidator {

    /**
     * Recognized condense-owned file names permitted in data or config directories.
     */
    public static final Set<String> KNOWN_CONDENSE_FILES = Set.of(
        "condense.db",
        "condense.db-wal",
        "condense.db-shm",
        "config.toml",
        "trust.json",
        ".install_dir",
        ".condense_install_dir"
    );

    private final PlatformDirs platformDirs;
    private final Path binaryPath;

    public SafePathValidator(PlatformDirs platformDirs) {
        this(platformDirs, null);
    }

    public SafePathValidator(PlatformDirs platformDirs, Path binaryPath) {
        this.platformDirs = platformDirs;
        this.binaryPath = binaryPath != null ? binaryPath.toAbsolutePath().normalize() : null;
    }

    public enum Status {
        SAFE,
        OUTSIDE_ALLOWED_LOCATION,
        SYMLINK_ESCAPE,
        UNEXPECTED_CONTENTS,
        NOT_FOUND,
        ERROR
    }

    public record ValidationResult(
        Status status,
        String reason,
        List<Path> unexpectedEntries
    ) {
        public boolean isSafe() {
            return status == Status.SAFE;
        }

        public static ValidationResult safe(String reason) {
            return new ValidationResult(Status.SAFE, reason, Collections.emptyList());
        }

        public static ValidationResult outside(String reason) {
            return new ValidationResult(Status.OUTSIDE_ALLOWED_LOCATION, reason, Collections.emptyList());
        }

        public static ValidationResult symlinkEscape(String reason) {
            return new ValidationResult(Status.SYMLINK_ESCAPE, reason, Collections.emptyList());
        }

        public static ValidationResult unexpected(String reason, List<Path> unexpectedEntries) {
            return new ValidationResult(Status.UNEXPECTED_CONTENTS, reason, unexpectedEntries);
        }

        public static ValidationResult notFound(String reason) {
            return new ValidationResult(Status.NOT_FOUND, reason, Collections.emptyList());
        }

        public static ValidationResult error(String reason) {
            return new ValidationResult(Status.ERROR, reason, Collections.emptyList());
        }
    }

    /**
     * Returns the list of runtime-resolved base directories that condense is allowed to manage.
     */
    public List<Path> getAllowedBaseDirectories() {
        List<Path> dirs = new ArrayList<>();
        if (platformDirs != null) {
            Path config = platformDirs.resolveConfigDir();
            if (config != null) {
                dirs.add(canonicalizeQuietly(config));
            }
            Path data = platformDirs.resolveDataDir();
            if (data != null) {
                dirs.add(canonicalizeQuietly(data));
            }
        }
        return Collections.unmodifiableList(dirs);
    }

    /**
     * Result of {@link #contain(Path, Path)} — a file must resolve inside {@code expectedParent}.
     */
    public record ContainmentResult(boolean contained, String reason, Path realFile, Path realParent) {
        public static ContainmentResult ok(Path realFile, Path realParent) {
            return new ContainmentResult(true, "contained", realFile, realParent);
        }

        public static ContainmentResult rejected(String reason) {
            return new ContainmentResult(false, reason, null, null);
        }
    }

    /**
     * Canonicalize {@code file} and require it to stay inside {@code expectedParent}.
     * Used for project {@code .condense/filters.toml} (outside condense-owned dirs)
     * and for writes into the config directory.
     */
    public static ContainmentResult contain(Path file, Path expectedParent) {
        if (file == null || expectedParent == null) {
            return ContainmentResult.rejected("file or parent is null");
        }
        try {
            Path realParent = Files.exists(expectedParent)
                ? expectedParent.toRealPath()
                : expectedParent.toAbsolutePath().normalize();
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                Path realFile = file.toRealPath();
                if (!realFile.startsWith(realParent)) {
                    return ContainmentResult.rejected(
                        "Path traversal or symlink escape: file '" + realFile
                            + "' resolves outside expected directory '" + realParent + "'"
                    );
                }
                return ContainmentResult.ok(realFile, realParent);
            }
            Path normalized = file.toAbsolutePath().normalize();
            Path normalizedParent = expectedParent.toAbsolutePath().normalize();
            if (!normalized.startsWith(realParent) && !normalized.startsWith(normalizedParent)) {
                return ContainmentResult.rejected(
                    "Path '" + normalized + "' is outside expected directory '" + realParent + "'"
                );
            }
            return ContainmentResult.ok(normalized, realParent);
        } catch (IOException e) {
            return ContainmentResult.rejected(
                "Cannot resolve canonical path for '" + file + "': " + e.getMessage()
            );
        }
    }

    /**
     * Checks if a target path resides within an allowed condense directory.
     */
    public boolean isInsideCondenseOwnedLocation(Path target) {
        if (target == null) {
            return false;
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        for (Path allowed : getAllowedBaseDirectories()) {
            if (normalizedTarget.startsWith(allowed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates a single file target before deletion.
     */
    public ValidationResult validateFileTarget(Path target) {
        if (target == null) {
            return ValidationResult.error("Target path is null");
        }

        // If target is the registered binary path
        if (binaryPath != null && target.toAbsolutePath().normalize().equals(binaryPath)) {
            return validateBinarySelf(target);
        }

        Path normalized = target.toAbsolutePath().normalize();

        // Check if the nominal path is inside an allowed directory
        if (!isInsideCondenseOwnedLocation(normalized)) {
            return ValidationResult.outside("Path '" + target + "' is outside condense-owned directories.");
        }

        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return ValidationResult.notFound("Target '" + target + "' does not exist.");
        }

        // Check symlink safety
        if (Files.isSymbolicLink(target)) {
            try {
                Path realPath = target.toRealPath();
                if (!isInsideCondenseOwnedLocation(realPath)) {
                    return ValidationResult.symlinkEscape(
                        "Safety refusal: Symlink '" + target + "' points outside condense-owned directories to '" + realPath + "'."
                    );
                }
            } catch (IOException e) {
                return ValidationResult.symlinkEscape("Safety refusal: Cannot resolve symlink '" + target + "': " + e.getMessage());
            }
        } else {
            try {
                Path realPath = target.toRealPath();
                if (!isInsideCondenseOwnedLocation(realPath)) {
                    return ValidationResult.symlinkEscape(
                        "Safety refusal: Real path for '" + target + "' resolves outside condense-owned directories to '" + realPath + "'."
                    );
                }
            } catch (IOException e) {
                // If toRealPath fails on an existing non-symlink file, reject for safety
                return ValidationResult.error("Cannot resolve real path for '" + target + "': " + e.getMessage());
            }
        }

        return ValidationResult.safe("Target '" + target + "' is valid and safe for removal.");
    }

    /**
     * Validates a directory before deletion, ensuring that every entry inside it
     * is individually recognized as a condense-owned file.
     */
    public ValidationResult validateDirectoryForDeletion(Path dir) {
        if (dir == null) {
            return ValidationResult.error("Directory path is null");
        }

        Path normalized = dir.toAbsolutePath().normalize();

        if (!isInsideCondenseOwnedLocation(normalized)) {
            return ValidationResult.outside("Directory '" + dir + "' is outside condense-owned locations.");
        }

        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return ValidationResult.notFound("Directory '" + dir + "' does not exist.");
        }

        if (!Files.isDirectory(dir)) {
            return ValidationResult.error("Path '" + dir + "' is not a directory.");
        }

        // Check symlink on directory itself
        if (Files.isSymbolicLink(dir)) {
            try {
                Path realDir = dir.toRealPath();
                if (!isInsideCondenseOwnedLocation(realDir)) {
                    return ValidationResult.symlinkEscape(
                        "Safety refusal: Directory symlink '" + dir + "' points outside allowed locations to '" + realDir + "'."
                    );
                }
            } catch (IOException e) {
                return ValidationResult.symlinkEscape("Safety refusal: Cannot resolve directory symlink '" + dir + "': " + e.getMessage());
            }
        }

        List<Path> unexpected = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    // Unexpected subdirectory inside condense data/config root
                    unexpected.add(entry);
                } else if (!KNOWN_CONDENSE_FILES.contains(fileName)) {
                    // Unrecognized file
                    unexpected.add(entry);
                } else if (Files.isSymbolicLink(entry)) {
                    // Even if known filename, if it's a symlink pointing outside, reject
                    try {
                        Path realEntry = entry.toRealPath();
                        if (!isInsideCondenseOwnedLocation(realEntry)) {
                            return ValidationResult.symlinkEscape(
                                "Safety refusal: Entry symlink '" + entry + "' points outside allowed locations to '" + realEntry + "'."
                            );
                        }
                    } catch (IOException e) {
                        return ValidationResult.symlinkEscape("Safety refusal: Cannot resolve entry symlink '" + entry + "': " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            return ValidationResult.error("Failed to inspect directory contents for '" + dir + "': " + e.getMessage());
        }

        if (!unexpected.isEmpty()) {
            return ValidationResult.unexpected(
                "Directory '" + dir + "' contains unexpected or unrecognized files. Directory removal aborted to prevent accidental data loss.",
                unexpected
            );
        }

        return ValidationResult.safe("Directory '" + dir + "' contains only recognized condense files and is safe for deletion.");
    }

    private ValidationResult validateBinarySelf(Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return ValidationResult.notFound("Binary '" + target + "' does not exist.");
        }
        return ValidationResult.safe("Binary '" + target + "' is valid for self-deletion.");
    }

    private static Path canonicalizeQuietly(Path path) {
        try {
            if (Files.exists(path)) {
                return path.toRealPath();
            }
        } catch (IOException ignored) {
        }
        return path.toAbsolutePath().normalize();
    }
}
