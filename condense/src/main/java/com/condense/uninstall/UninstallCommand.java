package com.condense.uninstall;

import com.condense.core.PackageManagerDetector;
import com.condense.core.PlatformDirs;
import com.condense.core.SafePathValidator;
import com.condense.hooks.HookInstaller;
import com.condense.hooks.HookTool;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * Subcommand to safely uninstall Condense, clean up PATH entries,
 * and optionally purge all configuration, database files, and hooks.
 */
@Command(
    name = "uninstall",
    description = "Uninstall Condense and optionally remove all data, configuration, and hooks.",
    mixinStandardHelpOptions = true
)
@Dependent
public class UninstallCommand implements Callable<Integer> {

    private static final Logger log = Logger.getLogger(UninstallCommand.class);

    @Option(
        names = "--purge",
        description = "Remove all configuration, database files, installed AI hooks, and install tracking metadata."
    )
    boolean purge;

    @Option(
        names = "--yes",
        description = "Skip interactive confirmation prompt. Required in non-interactive environments with --purge."
    )
    boolean yes;

    @Inject
    PlatformDirs platformDirs;

    @Inject
    HookInstaller hookInstaller;

    @Override
    public Integer call() {
        List<String> removedItems = new ArrayList<>();
        List<String> skippedOrFailedItems = new ArrayList<>();
        List<String> manualCleanupCommands = new ArrayList<>();

        Path binaryPath = resolveBinaryPath();

        // 1. Package manager detection
        boolean skipBinarySelfDelete = false;
        if (binaryPath != null) {
            Optional<PackageManagerDetector.Detection> detection = PackageManagerDetector.detect(binaryPath);
            if (detection.isPresent()) {
                PackageManagerDetector.Detection pm = detection.get();
                System.out.println("Notice: Condense appears to have been installed via " + pm.managerName() + ".");
                System.out.println("To remove the binary, use: " + pm.uninstallCommand());
                if (!purge) {
                    System.out.println("If you also want to remove data and configuration, run: condense uninstall --purge");
                    return 0;
                }
                System.out.println("Proceeding with data, configuration, and hook removal...");
                skipBinarySelfDelete = true;
            }
        }

        // 2. Non-interactive guard for --purge
        if (purge && !yes) {
            if (System.console() == null && System.getProperty("condense.test.interactive") == null) {
                System.err.println("condense: error: '--purge' requires '--yes' when running in a non-interactive terminal (e.g. scripts or CI).");
                System.err.println("Run 'condense uninstall --purge --yes' to confirm complete data removal.");
                return 1;
            }
        }

        // 3. Interactive confirmation prompt (for --purge without --yes in interactive shell)
        if (purge && !yes) {
            if (!confirmPurge(binaryPath, skipBinarySelfDelete)) {
                System.out.println("Uninstall aborted.");
                return 0;
            }
        }

        SafePathValidator validator = new SafePathValidator(platformDirs, binaryPath);

        // 4. If --purge is requested: clean up hooks, database, config, and data directories
        if (purge) {
            purgeHooks(removedItems, skippedOrFailedItems, manualCleanupCommands);
            purgeDatabaseAndConfig(validator, removedItems, skippedOrFailedItems, manualCleanupCommands);
            purgeInstallTracking(validator, removedItems, skippedOrFailedItems, manualCleanupCommands);
            purgeDirectories(validator, removedItems, skippedOrFailedItems, manualCleanupCommands);
        }

        // 5. Binary and PATH removal
        if (!skipBinarySelfDelete) {
            removePathEntry(binaryPath, removedItems, skippedOrFailedItems, manualCleanupCommands);
            removeBinary(binaryPath, validator, removedItems, skippedOrFailedItems, manualCleanupCommands);
        }

        // 6. Print final itemized report
        printFinalReport(removedItems, skippedOrFailedItems, manualCleanupCommands);

        return skippedOrFailedItems.isEmpty() ? 0 : 1;
    }

    private Path resolveBinaryPath() {
        String testBinary = System.getProperty("condense.test.binary");
        if (testBinary != null && !testBinary.isBlank()) {
            return Path.of(testBinary).toAbsolutePath().normalize();
        }

        String command = ProcessHandle.current().info().command().orElse(null);
        if (command != null && !command.isBlank()) {
            Path p = Path.of(command).toAbsolutePath().normalize();
            String name = p.getFileName() != null ? p.getFileName().toString().toLowerCase(Locale.ROOT) : "";
            if (!name.contains("java")) {
                return p;
            }
        }

        // Fallback for Unix (/proc/self/exe)
        Path procSelf = Path.of("/proc/self/exe");
        if (Files.exists(procSelf)) {
            try {
                return procSelf.toRealPath();
            } catch (IOException ignored) {
            }
        }

        // Fallback for Windows install tracking
        String winInstallDir = System.getenv("CONDENSE_INSTALL_DIR");
        if (winInstallDir != null && !winInstallDir.isBlank()) {
            Path candidate = Path.of(winInstallDir, "condense.exe");
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }

        // Fallback for Unix install tracking
        if (platformDirs != null) {
            Path unixInstallFile = platformDirs.resolveDataDir().resolve(".install_dir");
            if (Files.exists(unixInstallFile)) {
                try {
                    String line = Files.readString(unixInstallFile).trim();
                    if (!line.isBlank()) {
                        Path candidate = Path.of(line, "condense");
                        if (Files.exists(candidate)) {
                            return candidate.toAbsolutePath().normalize();
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }

        return null;
    }

    private boolean confirmPurge(Path binaryPath, boolean skipBinary) {
        System.out.println("\nCondense Uninstall & Purge Plan");
        System.out.println("===============================");
        System.out.println("The following components and files will be permanently removed:");
        if (binaryPath != null && !skipBinary) {
            System.out.println("  • Binary:    " + binaryPath);
        }
        if (platformDirs != null) {
            System.out.println("  • Database:  " + platformDirs.resolveDataDir().resolve("condense.db"));
            System.out.println("  • Config:    " + platformDirs.resolveConfigDir().resolve("config.toml"));
            System.out.println("  • Data dir:  " + platformDirs.resolveDataDir());
            System.out.println("  • Config dir:" + platformDirs.resolveConfigDir());
        }
        if (hookInstaller != null) {
            List<HookTool> installed = hookInstaller.listInstalled();
            if (!installed.isEmpty()) {
                System.out.println("  • Installed AI Hooks:");
                for (HookTool tool : installed) {
                    System.out.println("      - " + tool.displayName);
                }
            }
        }
        System.out.println();
        System.out.print("Proceed with purge? [y/N]: ");
        System.out.flush();

        String line = null;
        if (System.console() != null) {
            line = System.console().readLine();
        } else {
            Scanner scanner = new Scanner(System.in);
            if (scanner.hasNextLine()) {
                line = scanner.nextLine();
            }
        }

        return line != null && line.trim().equalsIgnoreCase("y");
    }

    private void purgeHooks(List<String> removed, List<String> failed, List<String> manual) {
        if (hookInstaller == null) {
            return;
        }
        try {
            List<HookInstaller.RemoveResult> results = hookInstaller.removeAll();
            for (HookInstaller.RemoveResult r : results) {
                if (r.removed()) {
                    removed.add("Removed AI hook for " + r.tool().displayName);
                } else if (r.message().startsWith("✗")) {
                    failed.add("Failed to remove AI hook for " + r.tool().displayName + " (" + r.message() + ")");
                    manual.add("condense init --remove");
                }
            }
        } catch (Exception e) {
            failed.add("Failed to clean up AI hooks: " + e.getMessage());
            manual.add("condense init --remove");
        }
    }

    private void purgeDatabaseAndConfig(
        SafePathValidator validator,
        List<String> removed,
        List<String> failed,
        List<String> manual
    ) {
        if (platformDirs == null) {
            return;
        }

        // Database and SQLite auxiliary files
        Path dataDir = platformDirs.resolveDataDir();
        Path db = dataDir.resolve("condense.db");
        Path dbWal = dataDir.resolve("condense.db-wal");
        Path dbShm = dataDir.resolve("condense.db-shm");

        for (Path file : List.of(db, dbWal, dbShm)) {
            if (Files.exists(file)) {
                SafePathValidator.ValidationResult validation = validator.validateFileTarget(file);
                if (validation.isSafe()) {
                    try {
                        Files.delete(file);
                        removed.add("Removed " + file);
                    } catch (IOException e) {
                        failed.add("Failed to delete " + file + ": " + e.getMessage());
                        manual.add("rm -f \"" + file + "\"");
                    }
                } else {
                    failed.add("Skipped deleting " + file + " due to safety check: " + validation.reason());
                }
            }
        }

        // Config file
        Path configDir = platformDirs.resolveConfigDir();
        Path config = configDir.resolve("config.toml");
        if (Files.exists(config)) {
            SafePathValidator.ValidationResult validation = validator.validateFileTarget(config);
            if (validation.isSafe()) {
                try {
                    Files.delete(config);
                    removed.add("Removed " + config);
                } catch (IOException e) {
                    failed.add("Failed to delete " + config + ": " + e.getMessage());
                    manual.add("rm -f \"" + config + "\"");
                }
            } else {
                failed.add("Skipped deleting " + config + " due to safety check: " + validation.reason());
            }
        }
    }

    private void purgeInstallTracking(
        SafePathValidator validator,
        List<String> removed,
        List<String> failed,
        List<String> manual
    ) {
        // Unix .install_dir metadata file
        if (platformDirs != null) {
            Path trackingFile = platformDirs.resolveDataDir().resolve(".install_dir");
            if (Files.exists(trackingFile)) {
                SafePathValidator.ValidationResult validation = validator.validateFileTarget(trackingFile);
                if (validation.isSafe()) {
                    try {
                        Files.delete(trackingFile);
                        removed.add("Removed install tracking file " + trackingFile);
                    } catch (IOException e) {
                        failed.add("Failed to remove tracking file " + trackingFile + ": " + e.getMessage());
                    }
                }
            }
        }

        // Windows CONDENSE_INSTALL_DIR environment variable
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "[Environment]::SetEnvironmentVariable('CONDENSE_INSTALL_DIR', $null, 'User')"
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exit = process.waitFor();
                if (exit == 0) {
                    removed.add("Cleared CONDENSE_INSTALL_DIR environment variable");
                } else {
                    failed.add("Failed to clear CONDENSE_INSTALL_DIR registry environment variable");
                }
            } catch (Exception e) {
                log.debugf("Could not clear Windows CONDENSE_INSTALL_DIR variable: %s", e.getMessage());
            }
        }
    }

    private void purgeDirectories(
        SafePathValidator validator,
        List<String> removed,
        List<String> failed,
        List<String> manual
    ) {
        if (platformDirs == null) {
            return;
        }

        Path dataDir = platformDirs.resolveDataDir();
        Path configDir = platformDirs.resolveConfigDir();

        List<Path> dirs = new ArrayList<>();
        if (Files.exists(dataDir)) {
            dirs.add(dataDir);
        }
        if (Files.exists(configDir) && !configDir.equals(dataDir)) {
            dirs.add(configDir);
        }

        for (Path dir : dirs) {
            SafePathValidator.ValidationResult validation = validator.validateDirectoryForDeletion(dir);
            if (validation.isSafe()) {
                try {
                    deleteDirectoryContentsAndSelf(dir);
                    removed.add("Removed directory " + dir);
                } catch (IOException e) {
                    failed.add("Failed to remove directory " + dir + ": " + e.getMessage());
                    manual.add("rm -rf \"" + dir + "\"");
                }
            } else if (validation.status() == SafePathValidator.Status.UNEXPECTED_CONTENTS) {
                failed.add("Left directory " + dir + " intact because it contains unrecognized files: " + validation.unexpectedEntries());
            } else {
                failed.add("Skipped directory " + dir + ": " + validation.reason());
            }
        }
    }

    private void removePathEntry(
        Path binaryPath,
        List<String> removed,
        List<String> failed,
        List<String> manual
    ) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String installDir = System.getenv("CONDENSE_INSTALL_DIR");
            if (installDir == null && binaryPath != null && binaryPath.getParent() != null) {
                installDir = binaryPath.getParent().toString();
            }
            if (installDir != null) {
                try {
                    String escapedDir = installDir.replace("'", "''");
                    String script = "$p = [Environment]::GetEnvironmentVariable('Path', 'User'); " +
                        "if ($p) { " +
                        "  $parts = $p -split ';' | Where-Object { $_ -and $_.TrimEnd('\\') -ne '" + escapedDir + "'.TrimEnd('\\') }; " +
                        "  [Environment]::SetEnvironmentVariable('Path', ($parts -join ';'), 'User'); " +
                        "}";
                    ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
                    pb.redirectErrorStream(true);
                    Process process = pb.start();
                    int exit = process.waitFor();
                    if (exit == 0) {
                        removed.add("Removed " + installDir + " from User PATH");
                    } else {
                        failed.add("Failed to remove " + installDir + " from User PATH");
                    }
                } catch (Exception e) {
                    failed.add("Could not update User PATH registry: " + e.getMessage());
                }
            }
        } else {
            // Unix: PATH is not modified automatically by install.sh
            log.debug("Unix PATH is user-managed via shell profiles.");
        }
    }

    private void removeBinary(
        Path binaryPath,
        SafePathValidator validator,
        List<String> removed,
        List<String> failed,
        List<String> manual
    ) {
        if (binaryPath == null) {
            failed.add("Could not resolve current executable binary location for self-deletion.");
            return;
        }

        if (!Files.exists(binaryPath)) {
            failed.add("Binary '" + binaryPath + "' does not exist (already removed?).");
            return;
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // Windows locks running executables. Spawn detached helper process to delete after process exits.
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "cmd.exe", "/c",
                    "ping 127.0.0.1 -n 2 >nul & del /f /q \"" + binaryPath.toAbsolutePath().toString() + "\""
                );
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                pb.start();
                removed.add("Scheduled self-deletion of executable: " + binaryPath);
            } catch (IOException e) {
                failed.add("Failed to schedule self-deletion of " + binaryPath + ": " + e.getMessage());
                manual.add("del /f /q \"" + binaryPath + "\"");
            }
        } else {
            // Linux / macOS: open inodes can be unlinked while running
            try {
                Files.delete(binaryPath);
                removed.add("Removed executable: " + binaryPath);
            } catch (IOException e) {
                failed.add("Failed to delete executable " + binaryPath + ": " + e.getMessage());
                manual.add("rm -f \"" + binaryPath + "\"");
            }
        }
    }

    private void deleteDirectoryContentsAndSelf(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    deleteDirectoryContentsAndSelf(entry);
                } else {
                    Files.delete(entry);
                }
            }
        }
        Files.delete(dir);
    }

    private void printFinalReport(
        List<String> removed,
        List<String> failed,
        List<String> manual
    ) {
        System.out.println("\nCondense Uninstall Summary");
        System.out.println("──────────────────────────");

        if (!removed.isEmpty()) {
            System.out.println("✓ Successfully removed:");
            for (String item : removed) {
                System.out.println("  • " + item);
            }
        }

        if (!failed.isEmpty()) {
            System.out.println("\n✗ Not removed / Warnings:");
            for (String item : failed) {
                System.out.println("  • " + item);
            }
        }

        if (!manual.isEmpty()) {
            System.out.println("\nManual cleanup commands:");
            for (String cmd : manual) {
                System.out.println("  " + cmd);
            }
        }

        System.out.println();
    }
}
