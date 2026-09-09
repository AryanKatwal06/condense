package com.condense.hooks;

import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

/**
 * {@code condense init} — installs, shows, or removes Condense hooks for AI coding tools.
 *
 * <p>Usage:
 * <pre>
 * condense init -g             # install hooks for all supported AI tools
 * condense init --show         # show which hooks are currently installed
 * condense init --remove       # remove all Condense-managed hooks
 * condense init --tool claude-code  # install only for a specific tool
 * </pre>
 */
@Command(
    name = "init",
    description = "Install AI tool hooks to transparently proxy commands through condense.",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "Supported tools: Claude Code, Cursor, Gemini CLI, Windsurf, Copilot, Cline,",
        "Codex, OpenCode, Kilo Code, Antigravity, Hermes, Pi",
        "",
        "MCP is the preferred agent path (`condense mcp --start`). Hooks are the fallback.",
        "Matched commands are denied with a retry message; they are never rewritten and allowed.",
        "",
        "Note: Claude Code handles multiple PreToolUse hooks in parallel with no guaranteed",
        "order. If you have competing hooks, condense's interception may not always take effect.",
        "",
        "Hooks can be removed at any time with `condense init --remove`."
    }
)
public class InitCommand implements java.util.concurrent.Callable<Integer>, Runnable {

    @Option(names = {"-g", "--global"},
        description = "Install hooks for all supported AI tools.")
    boolean global;

    @Option(names = "--show",
        description = "Show which AI tool hooks are currently installed, including integrity.")
    boolean show;

    @Option(names = "--remove",
        description = "Remove all Condense-managed hooks.")
    boolean remove;

    @Option(names = {"-n", "--dry-run"},
        description = "Simulate hook installation or update actions without modifying files.")
    boolean dryRun;

    @Option(names = "--update",
        description = "Update installed hooks to latest templates and repair tampered hooks.")
    boolean update;

    @Option(names = "--tool",
        description = "Target a specific tool only. " +
                      "Values: claude-code, cursor, gemini, windsurf, copilot, cline, " +
                      "codex, opencode, kilo, antigravity, hermes, pi",
        paramLabel = "TOOL")
    String tool;

    @Option(names = "--format",
        description = "Output format: 'text' (default) or 'json'.",
        defaultValue = "text", paramLabel = "FORMAT")
    String format;

    @Inject
    HookInstaller installer;

    @Override
    public void run() {
        call();
    }

    @Override
    public Integer call() {
        if (dryRun) {
            return runDryRun();
        } else if (update) {
            return runUpdate();
        } else if (show) {
            return runShow();
        } else if (remove) {
            return runRemove();
        } else if (tool != null) {
            return runInstallSingle();
        } else if (global) {
            return runInstallAll();
        } else {
            // No flag: show help guidance
            System.out.println("Usage: condense init -g        # install all hooks");
            System.out.println("       condense init --show    # show installed hooks");
            System.out.println("       condense init --update  # update installed hooks");
            System.out.println("       condense init -n        # preview actions without writing");
            System.out.println("       condense init --remove  # remove all hooks");
            System.out.println("       condense init --help    # full help");
            return 0;
        }
    }

    private Integer runDryRun() {
        System.out.println("Condense Hook Dry Run (no disk modifications)\n");
        if (tool != null) {
            HookTool target = parseTool(tool);
            if (target == null) return 1;
            HookInstaller.PlanResult plan = installer.plan(target);
            printPlan(plan);
        } else {
            List<HookInstaller.PlanResult> plans = installer.planAll();
            plans.forEach(this::printPlan);
        }
        return 0;
    }

    private void printPlan(HookInstaller.PlanResult p) {
        System.out.printf("  • %-20s  Action: %-10s  Backup: %-5s%n      Target: %s%n      Script: %s%n      Detail: %s%n%n",
            p.tool().displayName,
            p.action(),
            p.willBackup() ? "YES" : "NO",
            p.targetPath(),
            p.scriptPath(),
            p.description());
    }

    private Integer runUpdate() {
        System.out.println("Updating Condense hooks...\n");
        if (tool != null) {
            HookTool target = parseTool(tool);
            if (target == null) return 1;
            HookInstaller.InstallResult result = installer.update(target);
            System.out.println(result.message());
            return result.success() ? 0 : 1;
        } else {
            List<HookInstaller.InstallResult> results = installer.updateAll();
            if (results.isEmpty()) {
                System.out.println("No installed hooks found to update. Run 'condense init -g' to install hooks.");
                return 0;
            } else {
                results.forEach(r -> System.out.println(r.message()));
                long updated = results.stream().filter(HookInstaller.InstallResult::success).count();
                System.out.println("\n" + updated + "/" + results.size() + " installed hooks updated.");
                return updated == results.size() ? 0 : 1;
            }
        }
    }

    private Integer runInstallAll() {
        System.out.println("Installing Condense hooks for all supported AI tools...\n");
        List<HookInstaller.InstallResult> results = installer.installAll();
        results.forEach(r -> System.out.println(r.message()));
        long succeeded = results.stream().filter(HookInstaller.InstallResult::success).count();
        System.out.println("\n" + succeeded + "/" + results.size() + " hooks installed.");
        if (succeeded < results.size()) {
            System.out.println("Failed hooks are usually because the tool is not installed.");
            System.out.println("This is expected — only install hooks for tools you use.");
        }
        return succeeded > 0 ? 0 : 1;
    }

    private Integer runShow() {
        List<HookInstaller.StatusResult> statuses = installer.showAll();
        boolean hasTampered = false;
        for (HookInstaller.StatusResult r : statuses) {
            if (r.installed() && HookIntegrity.TAMPERED.equals(r.integrity())) {
                hasTampered = true;
            }
        }

        if ("json".equalsIgnoreCase(format)) {
            java.util.Map<String, Object> output = new java.util.LinkedHashMap<>();
            java.util.List<java.util.Map<String, Object>> hookList = new java.util.ArrayList<>();
            for (HookInstaller.StatusResult r : statuses) {
                java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
                map.put("tool", r.tool().name().toLowerCase().replace("_", "-"));
                map.put("display_name", r.tool().displayName);
                map.put("installed", r.installed());
                map.put("integrity", r.integrity() == null ? "none" : r.integrity());
                map.put("hook_file", r.hookFile() == null ? null : r.hookFile().toString());
                hookList.add(map);
            }
            output.put("hooks", hookList);
            output.put("tampered", hasTampered);
            try {
                System.out.println(com.condense.core.Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize hook status to JSON", e);
            }
            return hasTampered ? 1 : 0;
        }

        System.out.println("Condense Hook Status\n");
        System.out.printf("  %-20s  %-14s  %-12s  %s%n", "Tool", "Status", "Integrity", "Path");
        System.out.println("  " + "─".repeat(80));
        for (HookInstaller.StatusResult r : statuses) {
            System.out.printf("  %-20s  %-14s  %-12s  %s%n",
                r.tool().displayName,
                r.installed() ? "installed" : "not installed",
                r.integrity() == null ? "-" : r.integrity(),
                r.hookFile());
        }
        if (hasTampered) {
            System.out.println("\nWarning: One or more hooks have been modified or tampered with.");
            System.out.println("Run 'condense init --update' to restore them to baseline.");
            return 1;
        }
        return 0;
    }

    private Integer runRemove() {
        if (tool != null) {
            HookTool target = parseTool(tool);
            if (target == null) return 1;
            System.out.println("Removing Condense hook for " + target.displayName + "...\n");
            HookInstaller.RemoveResult result = installer.remove(target);
            System.out.println(result.message());
            return result.removed() ? 0 : 1;
        } else {
            System.out.println("Removing Condense-managed hooks...\n");
            installer.removeAll().forEach(r -> System.out.println(r.message()));
            return 0;
        }
    }

    private Integer runInstallSingle() {
        HookTool target = parseTool(tool);
        if (target == null) return 1;
        HookInstaller.InstallResult result = installer.install(target);
        System.out.println(result.message());
        return result.success() ? 0 : 1;
    }

    private HookTool parseTool(String name) {
        for (HookTool t : HookTool.values()) {
            if (t.name().equalsIgnoreCase(name.replace("-", "_"))
                    || t.displayName.equalsIgnoreCase(name)) {
                return t;
            }
        }
        System.err.println("condense init: unknown tool '" + name + "'");
        System.err.println("Valid values: " +
            java.util.Arrays.stream(HookTool.values())
                .map(t -> t.name().toLowerCase().replace("_", "-"))
                .collect(java.util.stream.Collectors.joining(", ")));
        return null;
    }
}
