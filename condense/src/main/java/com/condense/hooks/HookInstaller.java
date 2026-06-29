package com.condense.hooks;

import com.condense.core.ConfigLoader;
import com.condense.core.CondenseConfig;
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
 * Installs and manages Condense hooks for AI coding tools.
 *
 * <p>Each hook is a shell script (or JSON config) placed in the tool's hook
 * directory. The script intercepts shell commands and routes matching ones
 * through {@code condense} for output compression.
 *
 * <p>Hook files contain a sentinel comment {@link HookTemplate#SENTINEL} so
 * Condense can identify and remove them without disturbing other hook files.
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
        CondenseConfig config = configLoader.load();
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
        CondenseConfig config = configLoader.load();
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
     * Removes all Condense-managed hooks. Never removes non-Condense files.
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
        if (tool == HookTool.CLAUDE_CODE) {
            return installClaudeCode(tool, home, excluded);
        }
        if (tool == HookTool.CURSOR) {
            return installCursor(tool, home, excluded);
        }

        Path hookFile = tool.hookFile(home);
        try {
            // Load and customise template
            String template = HookTemplate.load(tool);
            String content  = HookTemplate.apply(tool, template, excluded);

            // Create parent directories
            Files.createDirectories(hookFile.getParent());

            // Write atomically
            Path tmp = Files.createTempFile(hookFile.getParent(), ".condense-hook-", ".tmp");
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
        if (tool == HookTool.CLAUDE_CODE) {
            return statusClaudeCode(tool, home);
        }
        if (tool == HookTool.CURSOR) {
            return statusCursor(tool, home);
        }

        Path hookFile = tool.hookFile(home);
        if (!Files.exists(hookFile)) {
            return new StatusResult(tool, false, hookFile);
        }
        try {
            String content = Files.readString(hookFile);
            boolean managed = HookTemplate.isManagedByCondense(content);
            return new StatusResult(tool, managed, hookFile);
        } catch (IOException e) {
            return new StatusResult(tool, false, hookFile);
        }
    }

    private RemoveResult remove(HookTool tool, Path home) {
        if (tool == HookTool.CLAUDE_CODE) {
            return removeClaudeCode(tool, home);
        }
        if (tool == HookTool.CURSOR) {
            return removeCursor(tool, home);
        }

        Path hookFile = tool.hookFile(home);
        if (!Files.exists(hookFile)) {
            return new RemoveResult(tool, false,
                "• " + tool.displayName + ": not installed");
        }
        try {
            String content = Files.readString(hookFile);
            if (!HookTemplate.isManagedByCondense(content)) {
                return new RemoveResult(tool, false,
                    "• " + tool.displayName + ": exists but was not installed by condense — skipped");
            }
            Files.delete(hookFile);
            return new RemoveResult(tool, true,
                "✓ Removed hook for " + tool.displayName);
        } catch (IOException e) {
            return new RemoveResult(tool, false,
                "✗ Failed to remove " + tool.displayName + ": " + e.getMessage());
        }
    }

    private InstallResult installClaudeCode(HookTool tool, Path home, List<String> excluded) {
        Path hookFile = tool.hookFile(home); // ~/.claude/settings.json
        Path scriptFile = home.resolve(".claude/hooks/condense-hook.sh");

        try {
            // 1. Write the script
            String template = HookTemplate.load(tool);
            String content = HookTemplate.apply(tool, template, excluded);
            Files.createDirectories(scriptFile.getParent());
            Files.writeString(scriptFile, content);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
                );
                Files.setPosixFilePermissions(scriptFile, perms);
            } catch (UnsupportedOperationException ignored) {}

            // 2. Update settings.json
            com.fasterxml.jackson.databind.node.ObjectNode root;
            if (Files.exists(hookFile)) {
                String existing = Files.readString(hookFile);
                if (existing.trim().isEmpty()) {
                    root = com.condense.core.Mappers.JSON.createObjectNode();
                } else {
                    root = (com.fasterxml.jackson.databind.node.ObjectNode) com.condense.core.Mappers.JSON.readTree(existing);
                }
            } else {
                root = com.condense.core.Mappers.JSON.createObjectNode();
            }

            com.fasterxml.jackson.databind.node.ObjectNode hooksNode;
            if (root.has("hooks") && root.get("hooks").isObject()) {
                hooksNode = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("hooks");
            } else {
                hooksNode = root.putObject("hooks");
            }

            com.fasterxml.jackson.databind.node.ArrayNode preToolUseNode;
            if (hooksNode.has("PreToolUse") && hooksNode.get("PreToolUse").isArray()) {
                preToolUseNode = (com.fasterxml.jackson.databind.node.ArrayNode) hooksNode.get("PreToolUse");
            } else {
                preToolUseNode = hooksNode.putArray("PreToolUse");
            }

            // Remove existing condense hook if it exists to avoid duplicates
            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = preToolUseNode.elements();
            while (it.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode node = it.next();
                if (node.has("hooks") && node.get("hooks").isArray()) {
                    com.fasterxml.jackson.databind.JsonNode hooksArr = node.get("hooks");
                    for (com.fasterxml.jackson.databind.JsonNode h : hooksArr) {
                        if (h.has("command") && h.get("command").asText().contains("condense-hook.sh")) {
                            it.remove();
                            break;
                        }
                    }
                }
            }

            // Check for competing Bash hooks
            boolean hasCompetingBashHook = false;
            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> checkIt = preToolUseNode.elements();
            while (checkIt.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode node = checkIt.next();
                if (node.has("matcher")) {
                    String matcherStr = node.get("matcher").asText("");
                    if (matcherStr.contains("Bash") || matcherStr.contains("Edit")) {
                        hasCompetingBashHook = true;
                        break;
                    }
                }
            }

            // Create new hook entry
            com.fasterxml.jackson.databind.node.ObjectNode hookEntry = com.condense.core.Mappers.JSON.createObjectNode();
            hookEntry.put("matcher", "Bash");
            com.fasterxml.jackson.databind.node.ArrayNode innerHooks = hookEntry.putArray("hooks");
            com.fasterxml.jackson.databind.node.ObjectNode innerHook = innerHooks.addObject();
            innerHook.put("type", "command");
            innerHook.put("command", scriptFile.toAbsolutePath().toString().replace("\\", "/"));
            innerHook.put("timeout", 30);

            preToolUseNode.add(hookEntry);

            Files.createDirectories(hookFile.getParent());
            Files.writeString(hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));

            log.infof("Installed hook for %s at %s", tool.displayName, hookFile);
            
            String warningMsg = "";
            if (hasCompetingBashHook) {
                warningMsg = "\n    Note: an existing Bash command hook is already configured in\n" +
                             "    ~/.claude/settings.json. Claude Code resolves multiple hooks that rewrite the\n" +
                             "    same command in parallel with no guaranteed order — if that hook also modifies\n" +
                             "    commands, condense's interception may not always take effect.";
            }
            
            return new InstallResult(tool, true,
                "✓ Installed hook for " + tool.displayName + " → " + hookFile + warningMsg);
        } catch (Exception e) {
            log.warnf("Failed to install hook for %s: %s", tool.displayName, e.getMessage());
            return new InstallResult(tool, false,
                "✗ Failed: " + tool.displayName + " — " + e.getMessage());
        }
    }

    private StatusResult statusClaudeCode(HookTool tool, Path home) {
        Path hookFile = tool.hookFile(home);
        if (!Files.exists(hookFile)) {
            return new StatusResult(tool, false, hookFile);
        }
        try {
            String existing = Files.readString(hookFile);
            if (existing.contains("condense-hook.sh")) {
                return new StatusResult(tool, true, hookFile);
            }
            return new StatusResult(tool, false, hookFile);
        } catch (IOException e) {
            return new StatusResult(tool, false, hookFile);
        }
    }

    private RemoveResult removeClaudeCode(HookTool tool, Path home) {
        Path hookFile = tool.hookFile(home);
        Path scriptFile = home.resolve(".claude/hooks/condense-hook.sh");

        boolean removedAnything = false;

        if (Files.exists(scriptFile)) {
            try { Files.delete(scriptFile); removedAnything = true; } catch (IOException ignored) {}
        }

        if (Files.exists(hookFile)) {
            try {
                String existing = Files.readString(hookFile);
                com.fasterxml.jackson.databind.JsonNode rootNode = com.condense.core.Mappers.JSON.readTree(existing);
                if (rootNode.isObject()) {
                    com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) rootNode;
                    if (root.has("hooks") && root.get("hooks").isObject()) {
                        com.fasterxml.jackson.databind.node.ObjectNode hooksNode = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("hooks");
                        if (hooksNode.has("PreToolUse") && hooksNode.get("PreToolUse").isArray()) {
                            com.fasterxml.jackson.databind.node.ArrayNode preToolUseNode = (com.fasterxml.jackson.databind.node.ArrayNode) hooksNode.get("PreToolUse");
                            boolean found = false;
                            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = preToolUseNode.elements();
                            while (it.hasNext()) {
                                com.fasterxml.jackson.databind.JsonNode node = it.next();
                                if (node.has("hooks") && node.get("hooks").isArray()) {
                                    com.fasterxml.jackson.databind.JsonNode hooksArr = node.get("hooks");
                                    for (com.fasterxml.jackson.databind.JsonNode h : hooksArr) {
                                        if (h.has("command") && h.get("command").asText().contains("condense-hook.sh")) {
                                            it.remove();
                                            found = true;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (found) {
                                Files.writeString(hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                                removedAnything = true;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (!removedAnything) {
            return new RemoveResult(tool, false, "• " + tool.displayName + ": not installed");
        }
        return new RemoveResult(tool, true, "✓ Removed hook for " + tool.displayName);
    }

    private InstallResult installCursor(HookTool tool, Path home, List<String> excluded) {
        Path hookFile = tool.hookFile(home); // ~/.cursor/hooks.json
        Path scriptFile = home.resolve(".cursor/hooks/condense-hook.sh");

        try {
            // 1. Write the script
            String template = HookTemplate.load(tool);
            String content = HookTemplate.apply(tool, template, excluded);
            Files.createDirectories(scriptFile.getParent());
            Files.writeString(scriptFile, content);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
                );
                Files.setPosixFilePermissions(scriptFile, perms);
            } catch (UnsupportedOperationException ignored) {}

            // 2. Update hooks.json
            com.fasterxml.jackson.databind.node.ObjectNode root;
            if (Files.exists(hookFile)) {
                String existing = Files.readString(hookFile);
                if (existing.trim().isEmpty()) {
                    root = com.condense.core.Mappers.JSON.createObjectNode();
                } else {
                    root = (com.fasterxml.jackson.databind.node.ObjectNode) com.condense.core.Mappers.JSON.readTree(existing);
                }
            } else {
                root = com.condense.core.Mappers.JSON.createObjectNode();
            }

            root.put("version", 1);

            com.fasterxml.jackson.databind.node.ObjectNode hooksNode;
            if (root.has("hooks") && root.get("hooks").isObject()) {
                hooksNode = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("hooks");
            } else {
                hooksNode = root.putObject("hooks");
            }

            com.fasterxml.jackson.databind.node.ArrayNode beforeShellExecNode;
            if (hooksNode.has("beforeShellExecution") && hooksNode.get("beforeShellExecution").isArray()) {
                beforeShellExecNode = (com.fasterxml.jackson.databind.node.ArrayNode) hooksNode.get("beforeShellExecution");
            } else {
                beforeShellExecNode = hooksNode.putArray("beforeShellExecution");
            }

            boolean hasExistingHooks = beforeShellExecNode.size() > 0;

            // Remove existing condense hook if it exists to avoid duplicates
            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = beforeShellExecNode.elements();
            while (it.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode node = it.next();
                if (node.has("command") && node.get("command").asText().contains("condense-hook.sh")) {
                    it.remove();
                }
            }
            
            // Re-evaluate after removal (in case condense was the only one)
            hasExistingHooks = beforeShellExecNode.size() > 0;

            // Create new hook entry
            com.fasterxml.jackson.databind.node.ObjectNode hookEntry = com.condense.core.Mappers.JSON.createObjectNode();
            hookEntry.put("command", scriptFile.toAbsolutePath().toString().replace("\\", "/"));

            beforeShellExecNode.add(hookEntry);

            Files.createDirectories(hookFile.getParent());
            Files.writeString(hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));

            log.infof("Installed hook for %s at %s", tool.displayName, hookFile);
            
            String warningMsg = "";
            if (hasExistingHooks) {
                warningMsg = "\n    Note: an existing beforeShellExecution hook is already configured.\n" +
                             "    Cursor resolves multiple shell-execution hooks in parallel and their\n" +
                             "    composition order is not guaranteed. If another hook modifies commands,\n" +
                             "    condense's interception may not reliably take effect.";
            }

            return new InstallResult(tool, true,
                "✓ Installed hook for " + tool.displayName + " → " + hookFile + warningMsg);
        } catch (Exception e) {
            log.warnf("Failed to install hook for %s: %s", tool.displayName, e.getMessage());
            return new InstallResult(tool, false,
                "✗ Failed: " + tool.displayName + " — " + e.getMessage());
        }
    }

    private StatusResult statusCursor(HookTool tool, Path home) {
        Path hookFile = tool.hookFile(home);
        if (!Files.exists(hookFile)) {
            return new StatusResult(tool, false, hookFile);
        }
        try {
            String existing = Files.readString(hookFile);
            if (existing.contains("condense-hook.sh")) {
                return new StatusResult(tool, true, hookFile);
            }
            return new StatusResult(tool, false, hookFile);
        } catch (IOException e) {
            return new StatusResult(tool, false, hookFile);
        }
    }

    private RemoveResult removeCursor(HookTool tool, Path home) {
        Path hookFile = tool.hookFile(home);
        Path scriptFile = home.resolve(".cursor/hooks/condense-hook.sh");

        boolean removedAnything = false;

        if (Files.exists(scriptFile)) {
            try { Files.delete(scriptFile); removedAnything = true; } catch (IOException ignored) {}
        }

        if (Files.exists(hookFile)) {
            try {
                String existing = Files.readString(hookFile);
                com.fasterxml.jackson.databind.JsonNode rootNode = com.condense.core.Mappers.JSON.readTree(existing);
                if (rootNode.isObject()) {
                    com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) rootNode;
                    if (root.has("hooks") && root.get("hooks").isObject()) {
                        com.fasterxml.jackson.databind.node.ObjectNode hooksNode = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("hooks");
                        if (hooksNode.has("beforeShellExecution") && hooksNode.get("beforeShellExecution").isArray()) {
                            com.fasterxml.jackson.databind.node.ArrayNode arrNode = (com.fasterxml.jackson.databind.node.ArrayNode) hooksNode.get("beforeShellExecution");
                            boolean found = false;
                            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = arrNode.elements();
                            while (it.hasNext()) {
                                com.fasterxml.jackson.databind.JsonNode node = it.next();
                                if (node.has("command") && node.get("command").asText().contains("condense-hook.sh")) {
                                    it.remove();
                                    found = true;
                                }
                            }
                            if (found) {
                                Files.writeString(hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                                removedAnything = true;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (!removedAnything) {
            return new RemoveResult(tool, false, "• " + tool.displayName + ": not installed");
        }
        return new RemoveResult(tool, true, "✓ Removed hook for " + tool.displayName);
    }

    static Path home() {
        String testHome = System.getProperty("condense.test.home");
        if (testHome != null && !testHome.isBlank()) {
            return Path.of(testHome);
        }
        return Path.of(System.getProperty("user.home"));
    }
}
