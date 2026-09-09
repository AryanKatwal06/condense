package com.condense.session;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Explicitly supported local agent session transcript formats.
 */
public enum AgentTranscriptFormat {

    CLAUDE_CODE("claude", "Claude Code", ".claude/projects", "*.jsonl"),
    CURSOR("cursor", "Cursor", ".cursor", "*.json"),
    WINDSURF("windsurf", "Windsurf", ".codeium/windsurf", "*.json");

    private final String id;
    private final String displayName;
    private final String defaultSubdir;
    private final String filePattern;

    AgentTranscriptFormat(String id, String displayName, String defaultSubdir, String filePattern) {
        this.id = id;
        this.displayName = displayName;
        this.defaultSubdir = defaultSubdir;
        this.filePattern = filePattern;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String defaultSubdir() {
        return defaultSubdir;
    }

    public String filePattern() {
        return filePattern;
    }

    public Path defaultBaseDir(Path userHome) {
        return userHome.resolve(defaultSubdir);
    }

    public static Optional<AgentTranscriptFormat> fromId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase();
        for (AgentTranscriptFormat format : values()) {
            if (format.id.equals(normalized) || format.name().toLowerCase().equals(normalized)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
