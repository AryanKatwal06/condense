package com.condense.hooks;

import java.nio.file.Path;

/**
 * Enumerates all AI coding tools that Condense can install hooks into.
 *
 * <p>Each entry declares:
 * <ul>
 *   <li>{@link #displayName} — human-readable name for install messages</li>
 *   <li>{@link #hookDir} — directory (relative to home) where the hook is placed</li>
 *   <li>{@link #hookFileName} — the hook file name</li>
 *   <li>{@link #templateResource} — classpath path to the bundled template</li>
 *   <li>{@link #isJson} — whether the hook file is JSON (vs shell script)</li>
 * </ul>
 */
public enum HookTool {

    GENERIC_BASH(
        "Generic Bash (Fallback)",
        ".condense/hooks",
        "pre-tool-use.sh",
        "/hooks/generic/pre-tool-use.sh",
        false
    ),
    CLAUDE_CODE(
        "Claude Code",
        ".claude",
        "settings.json",
        "/hooks/claude-code/condense-hook.sh",
        false
    ),
    CURSOR(
        "Cursor",
        ".cursor",
        "hooks.json",
        "/hooks/cursor/condense-hook.sh",
        false
    ),
    GEMINI(
        "Gemini CLI",
        ".gemini",
        "settings.json",
        "/hooks/gemini/condense-hook.sh",
        false
    ),
    WINDSURF(
        "Windsurf",
        ".codeium/windsurf",
        "hooks.json",
        "/hooks/windsurf/condense-hook.sh", // template for bash script
        true
    ),
    COPILOT(
        "GitHub Copilot CLI",
        ".copilot/hooks",
        "condense-hooks.json",
        "/hooks/copilot/condense-hook.sh", // template for bash script (also used by installer as a base path)
        true
    ),
    CLINE(
        "Cline",
        "Documents/Cline/Rules/Hooks",
        "PreToolUse",
        "/hooks/cline/PreToolUse",
        false
    ),
    CODEX(
        "Codex",
        ".codex",
        "hooks.json",
        "/hooks/codex/condense-hook.sh",
        false
    ),
    OPENCODE(
        "OpenCode",
        ".config/opencode",
        "hooks.json",
        "/hooks/opencode/condense-hook.js",
        false
    ),
    KILO(
        "Kilo Code",
        ".config/kilo",
        "hooks.json",
        "/hooks/kilo/condense-hook.sh",
        false
    ),
    ANTIGRAVITY(
        "Antigravity",
        ".gemini/antigravity-cli",
        "hooks.json",
        "/hooks/antigravity/condense-hook.sh",
        false
    ),
    HERMES(
        "Hermes",
        ".hermes/plugins/condense",
        "plugin.yaml",
        "/hooks/hermes/plugin.yaml",
        false
    ),
    PI(
        "Pi",
        ".pi/agent/extensions",
        "condense.ts",
        "/hooks/pi/condense.ts",
        false
    );

    public final String displayName;
    public final String hookDir;
    public final String hookFileName;
    public final String templateResource;
    public final boolean isJson;

    HookTool(String displayName, String hookDir, String hookFileName,
             String templateResource, boolean isJson) {
        this.displayName = displayName;
        this.hookDir = hookDir;
        this.hookFileName = hookFileName;
        this.templateResource = templateResource;
        this.isJson = isJson;
    }

    /** Returns the absolute hook file path for the given home directory. */
    public Path hookFile(Path homeDir) {
        return homeDir.resolve(hookDir).resolve(hookFileName);
    }

    /** Condense-owned script or plugin to hash. Not the third-party JSON config. */
    public Path ownedScript(Path homeDir) {
        return switch (this) {
            case CLAUDE_CODE -> homeDir.resolve(".claude/hooks/condense-hook.sh");
            case CURSOR -> homeDir.resolve(".cursor/hooks/condense-hook.sh");
            case GEMINI -> homeDir.resolve(".gemini/hooks/condense-hook.sh");
            case WINDSURF -> homeDir.resolve(".codeium/windsurf/hooks/condense-hook.sh");
            case COPILOT -> homeDir.resolve(".copilot/hooks/condense-hook.sh");
            case CODEX -> homeDir.resolve(".codex/hooks/condense-hook.sh");
            case ANTIGRAVITY -> homeDir.resolve(".gemini/antigravity-cli/hooks/condense-hook.sh");
            case KILO -> homeDir.resolve(".config/kilo/hooks/condense-hook.sh");
            case HERMES -> homeDir.resolve(".hermes/plugins/condense/__init__.py");
            case OPENCODE -> homeDir.resolve(".config/opencode/plugins/condense.js");
            case PI, GENERIC_BASH, CLINE -> hookFile(homeDir);
        };
    }
}
