package com.zapproxy.hooks;

import java.nio.file.Path;

/**
 * Enumerates all AI coding tools that Zap can install hooks into.
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

    CLAUDE_CODE(
        "Claude Code",
        ".claude/hooks",
        "pre-tool-use.sh",
        "/hooks/claude-code/pre-tool-use.sh",
        false
    ),
    CURSOR(
        "Cursor",
        ".cursor/hooks",
        "pre-tool-use.sh",
        "/hooks/cursor/pre-tool-use.sh",
        false
    ),
    GEMINI(
        "Gemini CLI",
        ".gemini/hooks",
        "pre-tool-use.sh",
        "/hooks/gemini/pre-tool-use.sh",
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
        "zap-settings.json",
        "/hooks/copilot/settings.json",
        true
    ),
    CLINE(
        "Cline",
        ".vscode",
        "zap-cline-settings.json",
        "/hooks/cline/settings.json",
        true
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
