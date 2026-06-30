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
        ".codeium/windsurf/hooks",
        "pre-tool-use.sh",
        "/hooks/windsurf/pre-tool-use.sh",
        false
    ),
    COPILOT(
        "GitHub Copilot",
        ".vscode",
        "condense-settings.json",
        "/hooks/copilot/settings.json",
        true
    ),
    CLINE(
        "Cline",
        "Documents/Cline/Rules/Hooks",
        "PreToolUse",
        "/hooks/cline/PreToolUse",
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
}
