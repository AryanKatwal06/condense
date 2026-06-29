package com.zapproxy.hooks;

import com.zapproxy.core.ConfigLoader;
import com.zapproxy.core.ZapConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Installs and manages Zap hooks for AI coding tools.
 *
 * <p>Each hook is a shell script (or JSON config) placed in the tool's hook
 * directory. The script intercepts shell commands and routes matching ones
 * through {@code zap} for output compression.
 *
 * <p>Hook files contain a sentinel comment {@link HookTemplate#SENTINEL} so
 * Zap can identify and remove them without disturbing other hook files.
 */
@ApplicationScoped
public class HookInstaller {

    private static final Logger log = Logger.getLogger(HookInstaller.class);

    @Inject
    ConfigLoader configLoader;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Installs hooks for all supported tools into the user's home directory.
     *
     * @return list of install results (one per tool)
     */
    public List<InstallResult> installAll() {
        ZapConfig config = configLoader.load();
        List<String> excluded = config.hooks().excludeCommands();
        Path home = home();
        List<InstallResult> results = new ArrayList<>();

        for (HookTool tool : HookTool.values()) {
            results.add(install(tool, home, excluded));
        }
        return results;
    }

    /**
     * Installs a hook for a single tool.
     *
     * @return install result describing success or failure
     */
    public InstallResult install(HookTool tool) {
        ZapConfig config = configLoader.load();
        return install(tool, home(), config.hooks().excludeCommands());
    }

    /**
     * Returns the install status of every supported tool.
     */
    public List<StatusResult> showAll() {
        Path home = home();
        List<StatusResult> results = new ArrayList<>();
        for (HookTool tool : HookTool.values()) {
            results.add(status(tool, home));
        }
        return results;
    }

    /**
     * Removes all Zap-managed hooks. Never removes non-Zap files.
     *
     * @return list of remove results (one per tool)
     */
    public List<RemoveResult> removeAll() {
        Path home = home();
        List<RemoveResult> results = new ArrayList<>();
        for (HookTool tool : HookTool.values()) {
            results.add(remove(tool, home));
        }
        return results;
    }

    // ── Result records ────────────────────────────────────────────────────────

    public record InstallResult(HookTool tool, boolean success, String message) {}
    public record StatusResult(HookTool tool, boolean installed, Path hookFile) {}
    public record RemoveResult(HookTool tool, boolean removed, String message) {}

    // ── Private implementation ────────────────────────────────────────────────

    private InstallResult install(HookTool tool, Path home, List<String> excluded) {
        Path hookFile = tool.hookFile(home);
        try {
            // Load and customise template
            String template = HookTemplate.load(tool);
            String content  = HookTemplate.apply(tool, template, excluded);

            // Create parent directories
            Files.createDirectories(hookFile.getParent());

            // Write atomically
            Path tmp = Files.createTempFile(hookFile.getParent(), ".zap-hook-", ".tmp");
            Files.writeString(tmp, content);

            // Make shell scripts executable (non-JSON hooks)
            if (!tool.isJson) {
                try {
                    Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE
                    );
                    Files.setPosixFilePermissions(tmp, perms);
                } catch (UnsupportedOperationException ignored) {
                    // Windows — no POSIX permissions
                }
            }

            Files.move(tmp, hookFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            log.infof("Installed hook for %s at %s", tool.displayName, hookFile);
            return new InstallResult(tool, true,
                "✓ Installed hook for " + tool.displayName + " → " + hookFile);

        } catch (IOException e) {
            log.warnf("Failed to install hook for %s: %s", tool.displayName, e.getMessage());
            return new InstallResult(tool, false,
                "✗ Failed: " + tool.displayName + " — " + e.getMessage());
        }
    }

    private StatusResult status(HookTool tool, Path home) {
        Path hookFile = tool.hookFile(home);
        if (!Files.exists(hookFile)) {
            return new StatusResult(tool, false, hookFile);
        }
        try {
            String content = Files.readString(hookFile);
            boolean managed = HookTemplate.isManagedByZap(content);
            return new StatusResult(tool, managed, hookFile);
        } catch (IOException e) {
            return new StatusResult(tool, false, hookFile);
        }
    }

    private RemoveResult remove(HookTool tool, Path home) {
        Path hookFile = tool.hookFile(home);
        if (!Files.exists(hookFile)) {
            return new RemoveResult(tool, false,
                "• " + tool.displayName + ": not installed");
        }
        try {
            String content = Files.readString(hookFile);
            if (!HookTemplate.isManagedByZap(content)) {
                return new RemoveResult(tool, false,
                    "• " + tool.displayName + ": exists but was not installed by zap — skipped");
            }
            Files.delete(hookFile);
            return new RemoveResult(tool, true,
                "✓ Removed hook for " + tool.displayName);
        } catch (IOException e) {
            return new RemoveResult(tool, false,
                "✗ Failed to remove " + tool.displayName + ": " + e.getMessage());
        }
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }
}
