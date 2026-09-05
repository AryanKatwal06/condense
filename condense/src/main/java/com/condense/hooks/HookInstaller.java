package com.condense.hooks;

import com.condense.core.ConfigLoader;
import com.condense.core.CondenseConfig;
import com.condense.core.PlatformDirs;
import com.condense.core.TrackingRepository;
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


@ApplicationScoped
public class HookInstaller {

    private static final Logger log = Logger.getLogger(HookInstaller.class);

    @Inject
    ConfigLoader configLoader;

    @Inject
    PlatformDirs platformDirs;

    @Inject
    TrackingRepository tracking;

    @Inject
    com.condense.core.StrategyRegistry strategyRegistry;



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
            results.add(decorateIntegrity(status(tool, home), home));
        }
        return results;
    }

    /**
     * Returns the list of tools that currently have a condense hook installed.
     * This method is read-only and side-effect-free.
     *
     * @return list of installed HookTool instances
     */
    public List<HookTool> listInstalled() {
        return showAll().stream()
            .filter(StatusResult::installed)
            .map(StatusResult::tool)
            .toList();
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



    public record InstallResult(HookTool tool, boolean success, String message) {}
    public record StatusResult(HookTool tool, boolean installed, Path hookFile, String integrity) {
        public StatusResult(HookTool tool, boolean installed, Path hookFile) {
            this(tool, installed, hookFile, installed ? HookIntegrity.OK : HookIntegrity.MISSING);
        }
    }
    public record RemoveResult(HookTool tool, boolean removed, String message) {}

    private String rendered(HookTool tool, String template, List<String> excluded) {
        return HookTemplate.apply(tool, template, excluded, strategyRegistry);
    }



    private InstallResult install(HookTool tool, Path home, List<String> excluded) {
        if (tool == HookTool.CLAUDE_CODE) {
            return installClaudeCode(tool, home, excluded);
        }
        if (tool == HookTool.CURSOR) {
            return installCursor(tool, home, excluded);
        }
        if (tool == HookTool.GEMINI) {
            return installGemini(tool, home, excluded);
        }
        if (tool == HookTool.CLINE) {
            return installCline(tool, home, excluded);
        }
        if (tool == HookTool.COPILOT) {
            return installCopilot(tool, home, excluded);
        }
        if (tool == HookTool.WINDSURF) {
            return installWindsurf(tool, home, excluded);
        }
        if (tool == HookTool.CODEX) {
            return installPreToolUse(tool, home, excluded,
                home.resolve(".codex/hooks.json"),
                home.resolve(".codex/hooks/condense-hook.sh"),
                "PreToolUse", "Bash");
        }
        if (tool == HookTool.ANTIGRAVITY) {
            return installPreToolUse(tool, home, excluded,
                home.resolve(".gemini/antigravity-cli/hooks.json"),
                home.resolve(".gemini/antigravity-cli/hooks/condense-hook.sh"),
                "PreToolUse", "run_command");
        }
        if (tool == HookTool.KILO) {
            return installPreToolUse(tool, home, excluded,
                home.resolve(".config/kilo/hooks.json"),
                home.resolve(".config/kilo/hooks/condense-hook.sh"),
                "PreToolUse", "Bash");
        }
        if (tool == HookTool.OPENCODE) {
            return installOpenCode(tool, home, excluded);
        }
        if (tool == HookTool.HERMES) {
            return installHermes(tool, home, excluded);
        }
        if (tool == HookTool.PI) {
            return installOwnedPlugin(tool, home, excluded);
        }

        Path hookFile = tool.hookFile(home);
        try {
            String template = HookTemplate.load(tool);
            String content  = rendered(tool, template, excluded);

            Files.createDirectories(hookFile.getParent());

            Path tmp = Files.createTempFile(hookFile.getParent(), ".condense-hook-", ".tmp");
            Files.writeString(tmp, content);

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
            rememberOwnedScript(tool, hookFile);

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
        if (tool == HookTool.GEMINI) {
            return statusGemini(tool, home);
        }
        if (tool == HookTool.COPILOT) {
            return statusCopilot(tool, home);
        }
        if (tool == HookTool.WINDSURF) {
            return statusWindsurf(tool, home);
        }
        if (tool == HookTool.CODEX || tool == HookTool.ANTIGRAVITY || tool == HookTool.KILO) {
            return statusByMarker(tool, home, "condense-hook");
        }
        if (tool == HookTool.OPENCODE || tool == HookTool.HERMES || tool == HookTool.PI) {
            return statusOwned(tool, home);
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
        if (tool == HookTool.GEMINI) {
            return removeGemini(tool, home);
        }
        if (tool == HookTool.COPILOT) {
            return removeCopilot(tool, home);
        }
        if (tool == HookTool.WINDSURF) {
            return removeWindsurf(tool, home);
        }
        if (tool == HookTool.CODEX || tool == HookTool.ANTIGRAVITY || tool == HookTool.KILO) {
            return removePreToolUse(tool, home);
        }
        if (tool == HookTool.OPENCODE || tool == HookTool.HERMES || tool == HookTool.PI) {
            return removeOwned(tool, home);
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
            String content = rendered(tool, template, excluded);
            Files.createDirectories(scriptFile.getParent());
            writeOwnedScript(tool, scriptFile, content);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
                );
                Files.setPosixFilePermissions(scriptFile, perms);
            } catch (UnsupportedOperationException ignored) {
                // Windows — POSIX permissions not supported, no action needed
            }

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

            com.fasterxml.jackson.databind.node.ObjectNode hookEntry = com.condense.core.Mappers.JSON.createObjectNode();
            hookEntry.put("matcher", "Bash");
            com.fasterxml.jackson.databind.node.ArrayNode innerHooks = hookEntry.putArray("hooks");
            com.fasterxml.jackson.databind.node.ObjectNode innerHook = innerHooks.addObject();
            innerHook.put("type", "command");
            innerHook.put("command", scriptFile.toAbsolutePath().toString().replace("\\", "/"));
            innerHook.put("timeout", 30);

            preToolUseNode.add(hookEntry);

            Files.createDirectories(hookFile.getParent());
            writeThirdPartyConfig(tool, hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));

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
            try { Files.delete(scriptFile); removedAnything = true; } catch (IOException ignored) {
                log.debugf("Best-effort cleanup: failed to delete %s: %s", scriptFile, ignored.getMessage());
            }
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
                                writeThirdPartyConfig(tool, hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                                removedAnything = true;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                log.warnf("Failed to remove %s hook entry from config file: %s", tool.displayName, ignored.getMessage());
            }
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
            String content = rendered(tool, template, excluded);
            Files.createDirectories(scriptFile.getParent());
            writeOwnedScript(tool, scriptFile, content);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
                );
                Files.setPosixFilePermissions(scriptFile, perms);
            } catch (UnsupportedOperationException ignored) {
                // Windows — POSIX permissions not supported, no action needed
            }

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

            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = beforeShellExecNode.elements();
            while (it.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode node = it.next();
                if (node.has("command") && node.get("command").asText().contains("condense-hook.sh")) {
                    it.remove();
                }
            }
            
            hasExistingHooks = beforeShellExecNode.size() > 0;

            com.fasterxml.jackson.databind.node.ObjectNode hookEntry = com.condense.core.Mappers.JSON.createObjectNode();
            hookEntry.put("command", scriptFile.toAbsolutePath().toString().replace("\\", "/"));

            beforeShellExecNode.add(hookEntry);

            Files.createDirectories(hookFile.getParent());
            writeThirdPartyConfig(tool, hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));

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
            try { Files.delete(scriptFile); removedAnything = true; } catch (IOException ignored) {
                log.debugf("Best-effort cleanup: failed to delete %s: %s", scriptFile, ignored.getMessage());
            }
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
                                writeThirdPartyConfig(tool, hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                                removedAnything = true;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                log.warnf("Failed to remove %s hook entry from config file: %s", tool.displayName, ignored.getMessage());
            }
        }

        if (!removedAnything) {
            return new RemoveResult(tool, false, "• " + tool.displayName + ": not installed");
        }
        return new RemoveResult(tool, true, "✓ Removed hook for " + tool.displayName);
    }

    private InstallResult installGemini(HookTool tool, Path home, List<String> excluded) {
        Path hookFile = tool.hookFile(home); // ~/.gemini/settings.json
        Path scriptFile = home.resolve(".gemini/hooks/condense-hook.sh");

        try {
            // 1. Write the script
            String template = HookTemplate.load(tool);
            String content = rendered(tool, template, excluded);
            Files.createDirectories(scriptFile.getParent());
            writeOwnedScript(tool, scriptFile, content);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
                );
                Files.setPosixFilePermissions(scriptFile, perms);
            } catch (UnsupportedOperationException ignored) {
                // Windows — POSIX permissions not supported, no action needed
            }

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

            com.fasterxml.jackson.databind.node.ArrayNode beforeToolNode;
            if (hooksNode.has("BeforeTool") && hooksNode.get("BeforeTool").isArray()) {
                beforeToolNode = (com.fasterxml.jackson.databind.node.ArrayNode) hooksNode.get("BeforeTool");
            } else {
                beforeToolNode = hooksNode.putArray("BeforeTool");
            }

            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = beforeToolNode.elements();
            while (it.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode node = it.next();
                if (node.has("hooks") && node.get("hooks").isArray()) {
                    com.fasterxml.jackson.databind.JsonNode hooksArr = node.get("hooks");
                    for (com.fasterxml.jackson.databind.JsonNode h : hooksArr) {
                        if (h.has("name") && "condense-hook".equals(h.get("name").asText())) {
                            it.remove();
                            break;
                        } else if (h.has("command") && h.get("command").asText().contains("condense-hook.sh")) {
                            it.remove();
                            break;
                        }
                    }
                }
            }

            // Check for competing hooks
            boolean hasCompetingHook = false;
            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> checkIt = beforeToolNode.elements();
            while (checkIt.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode node = checkIt.next();
                if (node.has("matcher")) {
                    String matcherStr = node.get("matcher").asText("");
                    if (matcherStr.equals("run_shell_command") || matcherStr.equals("*")) {
                        hasCompetingHook = true;
                        break;
                    }
                }
            }

            com.fasterxml.jackson.databind.node.ObjectNode hookEntry = com.condense.core.Mappers.JSON.createObjectNode();
            hookEntry.put("matcher", "run_shell_command");
            com.fasterxml.jackson.databind.node.ArrayNode innerHooks = hookEntry.putArray("hooks");
            com.fasterxml.jackson.databind.node.ObjectNode innerHook = innerHooks.addObject();
            innerHook.put("name", "condense-hook");
            innerHook.put("type", "command");
            innerHook.put("command", scriptFile.toAbsolutePath().toString().replace("\\", "/"));
            innerHook.put("timeout", 5000);

            beforeToolNode.add(hookEntry);

            Files.createDirectories(hookFile.getParent());
            writeThirdPartyConfig(tool, hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));

            log.infof("Installed hook for %s at %s", tool.displayName, hookFile);
            
            String warningMsg = "";
            if (hasCompetingHook) {
                warningMsg = "\n    Note: an existing BeforeTool hook matching 'run_shell_command' is already\n" +
                             "    configured in ~/.gemini/settings.json. Gemini CLI runs multiple matching\n" +
                             "    hooks in parallel — please confirm they do not conflict.";
            }

            return new InstallResult(tool, true,
                "✓ Installed hook for " + tool.displayName + " → " + hookFile + warningMsg);
        } catch (Exception e) {
            log.warnf("Failed to install hook for %s: %s", tool.displayName, e.getMessage());
            return new InstallResult(tool, false,
                "✗ Failed: " + tool.displayName + " — " + e.getMessage());
        }
    }

    private StatusResult statusGemini(HookTool tool, Path home) {
        Path hookFile = tool.hookFile(home);
        if (!Files.exists(hookFile)) {
            return new StatusResult(tool, false, hookFile);
        }
        try {
            String existing = Files.readString(hookFile);
            if (existing.contains("condense-hook.sh") || existing.contains("\"condense-hook\"")) {
                return new StatusResult(tool, true, hookFile);
            }
            return new StatusResult(tool, false, hookFile);
        } catch (IOException e) {
            return new StatusResult(tool, false, hookFile);
        }
    }

    private RemoveResult removeGemini(HookTool tool, Path home) {
        Path hookFile = tool.hookFile(home);
        Path scriptFile = home.resolve(".gemini/hooks/condense-hook.sh");

        boolean removedAnything = false;

        if (Files.exists(scriptFile)) {
            try { Files.delete(scriptFile); removedAnything = true; } catch (IOException ignored) {
                log.debugf("Best-effort cleanup: failed to delete %s: %s", scriptFile, ignored.getMessage());
            }
        }

        if (Files.exists(hookFile)) {
            try {
                String existing = Files.readString(hookFile);
                com.fasterxml.jackson.databind.JsonNode rootNode = com.condense.core.Mappers.JSON.readTree(existing);
                if (rootNode.isObject()) {
                    com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) rootNode;
                    if (root.has("hooks") && root.get("hooks").isObject()) {
                        com.fasterxml.jackson.databind.node.ObjectNode hooksNode = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("hooks");
                        if (hooksNode.has("BeforeTool") && hooksNode.get("BeforeTool").isArray()) {
                            com.fasterxml.jackson.databind.node.ArrayNode beforeToolNode = (com.fasterxml.jackson.databind.node.ArrayNode) hooksNode.get("BeforeTool");
                            boolean found = false;
                            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = beforeToolNode.elements();
                            while (it.hasNext()) {
                                com.fasterxml.jackson.databind.JsonNode node = it.next();
                                if (node.has("hooks") && node.get("hooks").isArray()) {
                                    com.fasterxml.jackson.databind.JsonNode hooksArr = node.get("hooks");
                                    for (com.fasterxml.jackson.databind.JsonNode h : hooksArr) {
                                        if (h.has("name") && "condense-hook".equals(h.get("name").asText())) {
                                            it.remove();
                                            found = true;
                                            break;
                                        } else if (h.has("command") && h.get("command").asText().contains("condense-hook.sh")) {
                                            it.remove();
                                            found = true;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (found) {
                                writeThirdPartyConfig(tool, hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                                removedAnything = true;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                log.warnf("Failed to remove %s hook entry from config file: %s", tool.displayName, ignored.getMessage());
            }
        }

        if (!removedAnything) {
            return new RemoveResult(tool, false, "• " + tool.displayName + ": not installed");
        }
        return new RemoveResult(tool, true, "✓ Removed hook for " + tool.displayName);
    }

    private InstallResult installCline(HookTool tool, Path home, List<String> excluded) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return new InstallResult(tool, false,
                "✗ Failed: " + tool.displayName + " — Cline hooks are not supported on Windows per Cline documentation.");
        }

        Path hookFile = tool.hookFile(home);
        try {
            if (Files.exists(hookFile)) {
                String existingContent = Files.readString(hookFile);
                if (!HookTemplate.isManagedByCondense(existingContent)) {
                    String snippet = 
                        "python3 -c '\n" +
                        "import sys, json\n" +
                        "try:\n" +
                        "    data = json.load(sys.stdin)\n" +
                        "    if data.get(\"preToolUse\", {}).get(\"toolName\") == \"execute_command\":\n" +
                        "        cmd = data.get(\"preToolUse\", {}).get(\"parameters\", {}).get(\"command\", \"\")\n" +
                        "        if cmd.strip().split()[0].split(\"/\")[-1] in \""
                            + HookCommands.spaceSeparated(strategyRegistry)
                            + "\".split():\n" +
                        "            print(json.dumps({\"cancel\": True, \"errorMessage\": \"Use \\\"condense <command>\\\" instead to get filtered, token-efficient output.\"}))\n" +
                        "            sys.exit(0)\n" +
                        "except Exception:\n" +
                        "    pass\n" +
                        "print(json.dumps({\"cancel\": False}))\n" +
                        "'";

                    return new InstallResult(tool, false,
                        "✗ Failed: " + tool.displayName + " — Refusing to overwrite existing unmanaged PreToolUse file at " + hookFile + ".\n" +
                        "  Cline only supports one PreToolUse script. You must manually merge condense's logic into your script:\n\n" +
                        "  # Add this snippet to your script to route commands to condense:\n" + snippet);
                }
            }

            String template = HookTemplate.load(tool);
            String content  = rendered(tool, template, excluded);

            Files.createDirectories(hookFile.getParent());

            Path tmp = Files.createTempFile(hookFile.getParent(), ".condense-hook-", ".tmp");
            Files.writeString(tmp, content);

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
                // Windows — no POSIX permissions, but we skip Windows above anyway.
            }

            Files.move(tmp, hookFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            rememberOwnedScript(tool, hookFile);

            log.infof("Installed hook for %s at %s", tool.displayName, hookFile);
            return new InstallResult(tool, true,
                "✓ Installed hook for " + tool.displayName + " → " + hookFile);

        } catch (IOException e) {
            log.warnf("Failed to install hook for %s: %s", tool.displayName, e.getMessage());
            return new InstallResult(tool, false,
                "✗ Failed: " + tool.displayName + " — " + e.getMessage());
        }
    }
    private Path getCopilotHooksDir(Path home) {
        String copilotHome = System.getenv("COPILOT_HOME");
        if (copilotHome != null && !copilotHome.trim().isEmpty()) {
            return Path.of(copilotHome).resolve("hooks");
        }
        return home.resolve(".copilot/hooks");
    }

    private InstallResult installCopilot(HookTool tool, Path home, List<String> excluded) {
        Path hooksDir = getCopilotHooksDir(home);
        Path hookFile = hooksDir.resolve(tool.hookFileName);
        Path bashScript = hooksDir.resolve("condense-hook.sh");
        Path psScript = hooksDir.resolve("condense-hook.ps1");

        try {
            String bashTemplate = HookTemplate.load(tool);
            String bashContent = rendered(tool, bashTemplate, excluded);
            Files.createDirectories(hooksDir);
            writeOwnedScript(tool, bashScript, bashContent);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
                );
                Files.setPosixFilePermissions(bashScript, perms);
            } catch (UnsupportedOperationException ignored) {
                // Windows — POSIX permissions not supported, no action needed
            }

            try (java.io.InputStream in = getClass().getResourceAsStream("/hooks/copilot/condense-hook.ps1")) {
                if (in != null) {
                    String psTemplate = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    String psContent = rendered(tool, psTemplate, excluded);
                    Files.writeString(psScript, psContent);
                }
            }

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

            com.fasterxml.jackson.databind.node.ArrayNode preToolUseNode;
            if (hooksNode.has("preToolUse") && hooksNode.get("preToolUse").isArray()) {
                preToolUseNode = (com.fasterxml.jackson.databind.node.ArrayNode) hooksNode.get("preToolUse");
            } else {
                preToolUseNode = hooksNode.putArray("preToolUse");
            }

            boolean hasExistingHooks = preToolUseNode.size() > 0;

            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = preToolUseNode.elements();
            while (it.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode node = it.next();
                if (node.has("bash") && node.get("bash").asText().contains("condense-hook.sh")) {
                    it.remove();
                }
            }
            
            hasExistingHooks = preToolUseNode.size() > 0;

            com.fasterxml.jackson.databind.node.ObjectNode hookEntry = com.condense.core.Mappers.JSON.createObjectNode();
            hookEntry.put("type", "command");
            hookEntry.put("bash", bashScript.toAbsolutePath().toString().replace("\\", "/"));
            hookEntry.put("powershell", psScript.toAbsolutePath().toString().replace("\\", "/"));
            hookEntry.put("timeoutSec", 15);

            preToolUseNode.add(hookEntry);

            writeThirdPartyConfig(tool, hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));

            log.infof("Installed hook for %s at %s", tool.displayName, hookFile);
            
            String warningMsg = "";
            if (hasExistingHooks) {
                warningMsg = "\n    Note: an existing preToolUse hook is already configured.\n" +
                             "    GitHub Copilot runs multiple hooks in sequence, and any one returning deny blocks the call.\n" +
                             "    Please confirm they do not conflict.";
            }

            return new InstallResult(tool, true,
                "✓ Installed hook for " + tool.displayName + " → " + hookFile + warningMsg);
        } catch (Exception e) {
            log.warnf("Failed to install hook for %s: %s", tool.displayName, e.getMessage());
            return new InstallResult(tool, false,
                "✗ Failed: " + tool.displayName + " — " + e.getMessage());
        }
    }

    private StatusResult statusCopilot(HookTool tool, Path home) {
        Path hookFile = getCopilotHooksDir(home).resolve(tool.hookFileName);
        if (!Files.exists(hookFile)) {
            return new StatusResult(tool, false, hookFile);
        }
        try {
            String existing = Files.readString(hookFile);
            if (existing.contains("condense-hook.sh") || existing.contains("condense-hook.ps1")) {
                return new StatusResult(tool, true, hookFile);
            }
            return new StatusResult(tool, false, hookFile);
        } catch (IOException e) {
            return new StatusResult(tool, false, hookFile);
        }
    }

    private RemoveResult removeCopilot(HookTool tool, Path home) {
        Path hooksDir = getCopilotHooksDir(home);
        Path hookFile = hooksDir.resolve(tool.hookFileName);
        Path bashScript = hooksDir.resolve("condense-hook.sh");
        Path psScript = hooksDir.resolve("condense-hook.ps1");

        boolean removedAnything = false;

        if (Files.exists(bashScript)) {
            try { Files.delete(bashScript); removedAnything = true; } catch (IOException ignored) {
                log.debugf("Best-effort cleanup: failed to delete %s: %s", bashScript, ignored.getMessage());
            }
        }
        if (Files.exists(psScript)) {
            try { Files.delete(psScript); removedAnything = true; } catch (IOException ignored) {
                log.debugf("Best-effort cleanup: failed to delete %s: %s", psScript, ignored.getMessage());
            }
        }

        if (Files.exists(hookFile)) {
            try {
                String existing = Files.readString(hookFile);
                com.fasterxml.jackson.databind.JsonNode rootNode = com.condense.core.Mappers.JSON.readTree(existing);
                if (rootNode.isObject()) {
                    com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) rootNode;
                    if (root.has("hooks") && root.get("hooks").isObject()) {
                        com.fasterxml.jackson.databind.node.ObjectNode hooksNode = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("hooks");
                        if (hooksNode.has("preToolUse") && hooksNode.get("preToolUse").isArray()) {
                            com.fasterxml.jackson.databind.node.ArrayNode arrNode = (com.fasterxml.jackson.databind.node.ArrayNode) hooksNode.get("preToolUse");
                            boolean found = false;
                            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = arrNode.elements();
                            while (it.hasNext()) {
                                com.fasterxml.jackson.databind.JsonNode node = it.next();
                                if (node.has("bash") && node.get("bash").asText().contains("condense-hook.sh")) {
                                    it.remove();
                                    found = true;
                                }
                            }
                            if (found) {
                                writeThirdPartyConfig(tool, hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                                removedAnything = true;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                log.warnf("Failed to remove %s hook entry from config file: %s", tool.displayName, ignored.getMessage());
            }
        }

        if (!removedAnything) {
            return new RemoveResult(tool, false, "• " + tool.displayName + ": not installed");
        }
        return new RemoveResult(tool, true, "✓ Removed hook for " + tool.displayName);
    }

    private InstallResult installWindsurf(HookTool tool, Path home, List<String> excluded) {
        Path hookFile = tool.hookFile(home); // ~/.codeium/windsurf/hooks.json
        Path bashScript = home.resolve(".codeium/windsurf/hooks/condense-hook.sh");
        Path psScript = home.resolve(".codeium/windsurf/hooks/condense-hook.ps1");

        try {
            String bashTemplate = HookTemplate.load(tool);
            String bashContent = rendered(tool, bashTemplate, excluded);
            Files.createDirectories(bashScript.getParent());
            writeOwnedScript(tool, bashScript, bashContent);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
                );
                Files.setPosixFilePermissions(bashScript, perms);
            } catch (UnsupportedOperationException ignored) {
                // Windows — POSIX permissions not supported, no action needed
            }

            try (java.io.InputStream in = getClass().getResourceAsStream("/hooks/windsurf/condense-hook.ps1")) {
                if (in != null) {
                    String psTemplate = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    String psContent = rendered(tool, psTemplate, excluded);
                    Files.writeString(psScript, psContent);
                }
            }

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

            com.fasterxml.jackson.databind.node.ArrayNode preRunCmdNode;
            if (hooksNode.has("pre_run_command") && hooksNode.get("pre_run_command").isArray()) {
                preRunCmdNode = (com.fasterxml.jackson.databind.node.ArrayNode) hooksNode.get("pre_run_command");
            } else {
                preRunCmdNode = hooksNode.putArray("pre_run_command");
            }

            boolean hasExistingHooks = preRunCmdNode.size() > 0;

            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = preRunCmdNode.elements();
            while (it.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode node = it.next();
                if (node.has("command") && node.get("command").asText().contains("condense-hook.sh")) {
                    it.remove();
                }
            }
            
            hasExistingHooks = preRunCmdNode.size() > 0;

            com.fasterxml.jackson.databind.node.ObjectNode hookEntry = com.condense.core.Mappers.JSON.createObjectNode();
            hookEntry.put("command", bashScript.toAbsolutePath().toString().replace("\\", "/"));
            hookEntry.put("powershell", psScript.toAbsolutePath().toString().replace("\\", "/"));
            hookEntry.put("show_output", true);

            preRunCmdNode.add(hookEntry);

            writeThirdPartyConfig(tool, hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));

            log.infof("Installed hook for %s at %s", tool.displayName, hookFile);
            
            String warningMsg = "\n    Note: Windsurf hook support uses Windsurf's Beta Cascade Hooks feature and may change without notice.";
            if (hasExistingHooks) {
                warningMsg += "\n    Note: an existing pre_run_command hook is already configured.\n" +
                              "    Windsurf may run multiple hooks in parallel or sequence, and any one returning an error blocks the call.\n" +
                              "    Please confirm they do not conflict.";
            }

            return new InstallResult(tool, true,
                "✓ Installed hook for " + tool.displayName + " → " + hookFile + warningMsg);
        } catch (Exception e) {
            log.warnf("Failed to install hook for %s: %s", tool.displayName, e.getMessage());
            return new InstallResult(tool, false,
                "✗ Failed: " + tool.displayName + " — " + e.getMessage());
        }
    }

    private StatusResult statusWindsurf(HookTool tool, Path home) {
        Path hookFile = tool.hookFile(home);
        if (!Files.exists(hookFile)) {
            return new StatusResult(tool, false, hookFile);
        }
        try {
            String existing = Files.readString(hookFile);
            if (existing.contains("condense-hook.sh") || existing.contains("condense-hook.ps1")) {
                return new StatusResult(tool, true, hookFile);
            }
            return new StatusResult(tool, false, hookFile);
        } catch (IOException e) {
            return new StatusResult(tool, false, hookFile);
        }
    }

    private RemoveResult removeWindsurf(HookTool tool, Path home) {
        Path hookFile = tool.hookFile(home);
        Path bashScript = home.resolve(".codeium/windsurf/hooks/condense-hook.sh");
        Path psScript = home.resolve(".codeium/windsurf/hooks/condense-hook.ps1");

        boolean removedAnything = false;

        if (Files.exists(bashScript)) {
            try { Files.delete(bashScript); removedAnything = true; } catch (IOException ignored) {
                log.debugf("Best-effort cleanup: failed to delete %s: %s", bashScript, ignored.getMessage());
            }
        }
        if (Files.exists(psScript)) {
            try { Files.delete(psScript); removedAnything = true; } catch (IOException ignored) {
                log.debugf("Best-effort cleanup: failed to delete %s: %s", psScript, ignored.getMessage());
            }
        }

        if (Files.exists(hookFile)) {
            try {
                String existing = Files.readString(hookFile);
                com.fasterxml.jackson.databind.JsonNode rootNode = com.condense.core.Mappers.JSON.readTree(existing);
                if (rootNode.isObject()) {
                    com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) rootNode;
                    if (root.has("hooks") && root.get("hooks").isObject()) {
                        com.fasterxml.jackson.databind.node.ObjectNode hooksNode = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("hooks");
                        if (hooksNode.has("pre_run_command") && hooksNode.get("pre_run_command").isArray()) {
                            com.fasterxml.jackson.databind.node.ArrayNode arrNode = (com.fasterxml.jackson.databind.node.ArrayNode) hooksNode.get("pre_run_command");
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
                                writeThirdPartyConfig(tool, hookFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                                removedAnything = true;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                log.warnf("Failed to remove %s hook entry from config file: %s", tool.displayName, ignored.getMessage());
            }
        }

        if (!removedAnything) {
            return new RemoveResult(tool, false, "• " + tool.displayName + ": not installed");
        }
        return new RemoveResult(tool, true, "✓ Removed hook for " + tool.displayName);
    }

    public static Path home() {
        String testHome = System.getProperty("condense.test.home");
        if (testHome != null && !testHome.isBlank()) {
            return Path.of(testHome);
        }
        String envHome = System.getenv("CONDENSE_TEST_HOME");
        if (envHome != null && !envHome.isBlank()) {
            return Path.of(envHome);
        }
        return Path.of(System.getProperty("user.home"));
    }

    private Path dataDir() {
        if (platformDirs != null) {
            return platformDirs.getDataDir();
        }
        String testHome = System.getProperty("condense.test.home");
        if (testHome != null && !testHome.isBlank()) {
            return Path.of(testHome).resolve("condense-data");
        }
        return Path.of(System.getProperty("user.home", "."), ".condense-data");
    }

    private void writeThirdPartyConfig(HookTool tool, Path hookFile, String json) throws IOException {
        Path backup = HookBackup.copyExisting(dataDir(), tool, hookFile);
        audit(tool, "backup", backup, true, backup == null ? "created" : backup.toString());
        Files.createDirectories(hookFile.getParent());
        Files.writeString(hookFile, json);
    }

    private void writeOwnedScript(HookTool tool, Path scriptFile, String content) throws IOException {
        Files.createDirectories(scriptFile.getParent());
        Files.writeString(scriptFile, content);
        rememberOwnedScript(tool, scriptFile);
    }

    private void rememberOwnedScript(HookTool tool, Path scriptFile) {
        try {
            String sha = HookIntegrity.hashFile(scriptFile);
            if (tracking != null) {
                tracking.upsertHookBaseline(tool.name(), scriptFile.toAbsolutePath().toString(), sha);
            }
            audit(tool, "install", scriptFile, true, sha);
        } catch (Exception e) {
            log.warnf("Hook baseline failed for %s: %s", tool.displayName, e.getMessage());
        }
    }

    private void audit(HookTool tool, String action, Path path, boolean success, String detail) {
        if (tracking == null) {
            return;
        }
        String sha = null;
        try {
            if (path != null && Files.isRegularFile(path)) {
                sha = HookIntegrity.hashFile(path);
            }
        } catch (IOException ignored) {
        }
        tracking.insertHookEvent(
            tool.name(),
            action,
            path == null ? null : path.toString(),
            sha,
            success,
            detail);
    }

    private StatusResult decorateIntegrity(StatusResult raw, Path home) {
        if (raw == null) {
            return raw;
        }
        if (!raw.installed()) {
            return new StatusResult(raw.tool(), false, raw.hookFile(), HookIntegrity.MISSING);
        }
        String integrity = HookIntegrity.verify(tracking, raw.tool(), raw.tool().ownedScript(home));
        return new StatusResult(raw.tool(), true, raw.hookFile(), integrity);
    }

    private StatusResult statusByMarker(HookTool tool, Path home, String marker) {
        Path hookFile = tool.hookFile(home);
        if (!Files.exists(hookFile)) {
            return new StatusResult(tool, false, hookFile);
        }
        try {
            return new StatusResult(tool, Files.readString(hookFile).contains(marker), hookFile);
        } catch (IOException e) {
            return new StatusResult(tool, false, hookFile);
        }
    }

    private StatusResult statusOwned(HookTool tool, Path home) {
        Path script = tool.ownedScript(home);
        if (!Files.exists(script)) {
            return new StatusResult(tool, false, script);
        }
        try {
            return new StatusResult(tool, HookTemplate.isManagedByCondense(Files.readString(script)), script);
        } catch (IOException e) {
            return new StatusResult(tool, false, script);
        }
    }

    private InstallResult installPreToolUse(
            HookTool tool,
            Path home,
            List<String> excluded,
            Path configFile,
            Path scriptFile,
            String eventKey,
            String matcher
    ) {
        try {
            String template = HookTemplate.load(tool);
            String content = rendered(tool, template, excluded);
            writeOwnedScript(tool, scriptFile, content);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
                );
                Files.setPosixFilePermissions(scriptFile, perms);
            } catch (UnsupportedOperationException ignored) {
            }

            com.fasterxml.jackson.databind.node.ObjectNode root;
            if (Files.exists(configFile) && Files.size(configFile) > 0) {
                root = (com.fasterxml.jackson.databind.node.ObjectNode) com.condense.core.Mappers.JSON.readTree(Files.readString(configFile));
            } else {
                root = com.condense.core.Mappers.JSON.createObjectNode();
            }
            com.fasterxml.jackson.databind.node.ObjectNode hooksNode = root.has("hooks") && root.get("hooks").isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) root.get("hooks")
                : root.putObject("hooks");
            com.fasterxml.jackson.databind.node.ArrayNode events = hooksNode.has(eventKey) && hooksNode.get(eventKey).isArray()
                ? (com.fasterxml.jackson.databind.node.ArrayNode) hooksNode.get(eventKey)
                : hooksNode.putArray(eventKey);
            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = events.elements();
            while (it.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode node = it.next();
                String blob = node.toString();
                if (blob.contains("condense-hook")) {
                    it.remove();
                }
            }
            com.fasterxml.jackson.databind.node.ObjectNode entry = events.addObject();
            entry.put("matcher", matcher);
            com.fasterxml.jackson.databind.node.ArrayNode inner = entry.putArray("hooks");
            com.fasterxml.jackson.databind.node.ObjectNode cmd = inner.addObject();
            cmd.put("type", "command");
            cmd.put("command", scriptFile.toAbsolutePath().toString().replace("\\", "/"));
            cmd.put("timeout", 30);
            writeThirdPartyConfig(tool, configFile, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            return new InstallResult(tool, true, "✓ Installed hook for " + tool.displayName + " → " + configFile);
        } catch (Exception e) {
            return new InstallResult(tool, false, "✗ Failed: " + tool.displayName + " — " + e.getMessage());
        }
    }

    private RemoveResult removePreToolUse(HookTool tool, Path home) {
        Path configFile = tool.hookFile(home);
        Path scriptFile = tool.ownedScript(home);
        boolean removed = false;
        if (Files.exists(scriptFile)) {
            try {
                Files.delete(scriptFile);
                removed = true;
            } catch (IOException ignored) {
            }
        }
        if (Files.exists(configFile)) {
            try {
                com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) com.condense.core.Mappers.JSON.readTree(Files.readString(configFile));
                if (root.has("hooks") && root.get("hooks").isObject()) {
                    com.fasterxml.jackson.databind.node.ObjectNode hooksNode =
                        (com.fasterxml.jackson.databind.node.ObjectNode) root.get("hooks");
                    for (String key : List.of("PreToolUse", "BeforeTool")) {
                        if (!hooksNode.has(key) || !hooksNode.get(key).isArray()) {
                            continue;
                        }
                        com.fasterxml.jackson.databind.node.ArrayNode arr =
                            (com.fasterxml.jackson.databind.node.ArrayNode) hooksNode.get(key);
                        java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = arr.elements();
                        while (it.hasNext()) {
                            if (it.next().toString().contains("condense-hook")) {
                                it.remove();
                                removed = true;
                            }
                        }
                    }
                    writeThirdPartyConfig(tool, configFile,
                        com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                }
            } catch (Exception e) {
                log.warnf("Failed to remove %s hook entry: %s", tool.displayName, e.getMessage());
            }
        }
        if (tracking != null) {
            tracking.deleteHookBaseline(tool.name());
        }
        audit(tool, "remove", scriptFile, removed, null);
        return removed
            ? new RemoveResult(tool, true, "✓ Removed hook for " + tool.displayName)
            : new RemoveResult(tool, false, "• " + tool.displayName + ": not installed");
    }

    private InstallResult installOwnedPlugin(HookTool tool, Path home, List<String> excluded) {
        try {
            Path dest = tool.ownedScript(home);
            String content = rendered(tool, HookTemplate.load(tool), excluded);
            writeOwnedScript(tool, dest, content);
            return new InstallResult(tool, true, "✓ Installed hook for " + tool.displayName + " → " + dest);
        } catch (Exception e) {
            return new InstallResult(tool, false, "✗ Failed: " + tool.displayName + " — " + e.getMessage());
        }
    }

    private InstallResult installOpenCode(HookTool tool, Path home, List<String> excluded) {
        try {
            Path plugin = tool.ownedScript(home);
            String content = rendered(tool, HookTemplate.load(tool), excluded);
            writeOwnedScript(tool, plugin, content);
            Path config = tool.hookFile(home);
            com.fasterxml.jackson.databind.node.ObjectNode root;
            if (Files.exists(config) && Files.size(config) > 0) {
                root = (com.fasterxml.jackson.databind.node.ObjectNode) com.condense.core.Mappers.JSON.readTree(Files.readString(config));
            } else {
                root = com.condense.core.Mappers.JSON.createObjectNode();
            }
            com.fasterxml.jackson.databind.node.ObjectNode hooks = root.has("hooks") && root.get("hooks").isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) root.get("hooks")
                : root.putObject("hooks");
            com.fasterxml.jackson.databind.node.ArrayNode before = hooks.has("PreToolUse") && hooks.get("PreToolUse").isArray()
                ? (com.fasterxml.jackson.databind.node.ArrayNode) hooks.get("PreToolUse")
                : hooks.putArray("PreToolUse");
            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = before.elements();
            while (it.hasNext()) {
                if (it.next().toString().contains("condense")) {
                    it.remove();
                }
            }
            com.fasterxml.jackson.databind.node.ObjectNode entry = before.addObject();
            entry.put("matcher", "Bash");
            com.fasterxml.jackson.databind.node.ArrayNode inner = entry.putArray("hooks");
            com.fasterxml.jackson.databind.node.ObjectNode cmd = inner.addObject();
            cmd.put("type", "command");
            cmd.put("command", "node " + plugin.toAbsolutePath().toString().replace("\\", "/"));
            writeThirdPartyConfig(tool, config, com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            return new InstallResult(tool, true, "✓ Installed hook for " + tool.displayName + " → " + config);
        } catch (Exception e) {
            return new InstallResult(tool, false, "✗ Failed: " + tool.displayName + " — " + e.getMessage());
        }
    }

    private InstallResult installHermes(HookTool tool, Path home, List<String> excluded) {
        try {
            Path yaml = tool.hookFile(home);
            Path py = tool.ownedScript(home);
            writeOwnedScript(tool, yaml, HookTemplate.load(tool));
            String pyTemplate;
            try (java.io.InputStream in = HookInstaller.class.getResourceAsStream("/hooks/hermes/__init__.py")) {
                if (in == null) {
                    throw new IOException("missing /hooks/hermes/__init__.py");
                }
                pyTemplate = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            writeOwnedScript(tool, py, rendered(tool, pyTemplate, excluded));
            Path config = home.resolve(".hermes/config.yaml");
            String existing = Files.exists(config) ? Files.readString(config) : "";
            if (!existing.contains("condense")) {
                writeThirdPartyConfig(tool, config, existing + (existing.endsWith("\n") || existing.isEmpty() ? "" : "\n")
                    + "plugins:\n  enabled:\n    - condense\n");
            }
            return new InstallResult(tool, true, "✓ Installed hook for " + tool.displayName + " → " + yaml);
        } catch (Exception e) {
            return new InstallResult(tool, false, "✗ Failed: " + tool.displayName + " — " + e.getMessage());
        }
    }

    private RemoveResult removeOwned(HookTool tool, Path home) {
        Path script = tool.ownedScript(home);
        boolean removed = false;
        if (Files.exists(script)) {
            try {
                if (HookTemplate.isManagedByCondense(Files.readString(script))) {
                    Files.delete(script);
                    removed = true;
                }
            } catch (IOException e) {
                return new RemoveResult(tool, false, "✗ Failed to remove " + tool.displayName + ": " + e.getMessage());
            }
        }
        Path config = tool.hookFile(home);
        if (config != null && Files.exists(config) && !config.equals(script)) {
            try {
                String text = Files.readString(config);
                if (text.contains("condense")) {
                    writeThirdPartyConfig(tool, config, text);
                }
            } catch (IOException ignored) {
            }
        }
        if (tracking != null) {
            tracking.deleteHookBaseline(tool.name());
        }
        audit(tool, "remove", script, removed, null);
        return removed
            ? new RemoveResult(tool, true, "✓ Removed hook for " + tool.displayName)
            : new RemoveResult(tool, false, "• " + tool.displayName + ": exists but was not installed by condense — skipped");
    }
}
