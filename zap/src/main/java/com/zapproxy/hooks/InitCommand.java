package com.zapproxy.hooks;

import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

/**
 * {@code zap init} — installs, shows, or removes Zap hooks for AI coding tools.
 *
 * <p>Usage:
 * <pre>
 * zap init -g             # install hooks for all supported AI tools
 * zap init --show         # show which hooks are currently installed
 * zap init --remove       # remove all Zap-managed hooks
 * zap init --tool claude-code  # install only for a specific tool
 * </pre>
 */
@Command(
    name = "init",
    description = "Install AI tool hooks to transparently proxy commands through zap.",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "Supported tools: Claude Code, Cursor, Gemini CLI, Windsurf, Copilot, Cline",
        "",
        "After running `zap init -g`, AI tools will automatically route matching",
        "commands through zap without any change to your workflow.",
        "",
        "Note: Claude Code handles multiple hooks rewriting the same command",
        "in parallel with no guaranteed order. If you have competing PreToolUse hooks,",
        "zap's interception may not always take effect.",
        "",
        "Hooks can be removed at any time with `zap init --remove`."
    }
)
public class InitCommand implements Runnable {

    @Option(names = {"-g", "--global"},
        description = "Install hooks for all supported AI tools.")
    boolean global;

    @Option(names = "--show",
        description = "Show which AI tool hooks are currently installed.")
    boolean show;

    @Option(names = "--remove",
        description = "Remove all Zap-managed hooks.")
    boolean remove;

    @Option(names = "--tool",
        description = "Install hook for a specific tool only. " +
                      "Values: claude-code, cursor, gemini, windsurf, copilot, cline",
        paramLabel = "TOOL")
    String tool;

    @Inject
    HookInstaller installer;

    @Override
    public void run() {
        if (show) {
            runShow();
        } else if (remove) {
            runRemove();
        } else if (tool != null) {
            runInstallSingle();
        } else if (global) {
            runInstallAll();
        } else {
            // No flag: show help guidance
            System.out.println("Usage: zap init -g        # install all hooks");
            System.out.println("       zap init --show    # show installed hooks");
            System.out.println("       zap init --remove  # remove all hooks");
            System.out.println("       zap init --help    # full help");
        }
    }

    private void runInstallAll() {
        System.out.println("Installing Zap hooks for all supported AI tools...\n");
        List<HookInstaller.InstallResult> results = installer.installAll();
        results.forEach(r -> System.out.println(r.message()));
        long succeeded = results.stream().filter(HookInstaller.InstallResult::success).count();
        System.out.println("\n" + succeeded + "/" + results.size() + " hooks installed.");
        if (succeeded < results.size()) {
            System.out.println("Failed hooks are usually because the tool is not installed.");
            System.out.println("This is expected — only install hooks for tools you use.");
        }
    }

    private void runShow() {
        System.out.println("Zap Hook Status\n");
        System.out.printf("  %-20s  %-12s  %s%n", "Tool", "Status", "Path");
        System.out.println("  " + "─".repeat(70));
        installer.showAll().forEach(r ->
            System.out.printf("  %-20s  %-12s  %s%n",
                r.tool().displayName,
                r.installed() ? "✓ installed" : "✗ not installed",
                r.hookFile()));
    }

    private void runRemove() {
        System.out.println("Removing Zap-managed hooks...\n");
        installer.removeAll().forEach(r -> System.out.println(r.message()));
    }

    private void runInstallSingle() {
        HookTool target = null;
        for (HookTool t : HookTool.values()) {
            if (t.name().equalsIgnoreCase(tool.replace("-", "_"))
                    || t.displayName.equalsIgnoreCase(tool)) {
                target = t;
                break;
            }
        }
        if (target == null) {
            System.err.println("zap init: unknown tool '" + tool + "'");
            System.err.println("Valid values: " +
                java.util.Arrays.stream(HookTool.values())
                    .map(t -> t.name().toLowerCase().replace("_", "-"))
                    .collect(java.util.stream.Collectors.joining(", ")));
            return;
        }
        HookInstaller.InstallResult result = installer.install(target);
        System.out.println(result.message());
    }
}
