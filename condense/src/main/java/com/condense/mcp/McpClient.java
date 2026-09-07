package com.condense.mcp;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Enumerates supported MCP client applications, their configuration locations,
 * and exact client-specific JSON/CLI snippets.
 */
public enum McpClient {

    CLAUDE_DESKTOP(
        "claude-desktop",
        "Claude Desktop",
        "Desktop app configuration file",
        """
        {
          "mcpServers": {
            "condense": {
              "command": "condense",
              "args": ["mcp", "--start"]
            }
          }
        }
        """,
        null
    ) {
        @Override
        public Path resolveConfigPath(Path home) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac")) {
                return home.resolve("Library/Application Support/Claude/claude_desktop_config.json");
            }
            if (os.contains("win")) {
                String appData = System.getenv("APPDATA");
                if (appData != null && !appData.isBlank()) {
                    return Path.of(appData, "Claude", "claude_desktop_config.json");
                }
                return home.resolve("AppData/Roaming/Claude/claude_desktop_config.json");
            }
            return home.resolve(".config/Claude/claude_desktop_config.json");
        }
    },

    CLAUDE_CODE(
        "claude-code",
        "Claude Code",
        "Claude Code CLI configuration",
        """
        {
          "mcpServers": {
            "condense": {
              "command": "condense",
              "args": ["mcp", "--start"]
            }
          }
        }
        """,
        "claude mcp add condense -- condense mcp --start"
    ) {
        @Override
        public Path resolveConfigPath(Path home) {
            return home.resolve(".claude.json");
        }
    },

    CURSOR(
        "cursor",
        "Cursor",
        "Cursor MCP configuration",
        """
        {
          "mcpServers": {
            "condense": {
              "command": "condense",
              "args": ["mcp", "--start"]
            }
          }
        }
        """,
        null
    ) {
        @Override
        public Path resolveConfigPath(Path home) {
            return home.resolve(".cursor/mcp.json");
        }
    },

    WINDSURF(
        "windsurf",
        "Windsurf",
        "Windsurf MCP configuration",
        """
        {
          "mcpServers": {
            "condense": {
              "command": "condense",
              "args": ["mcp", "--start"]
            }
          }
        }
        """,
        null
    ) {
        @Override
        public Path resolveConfigPath(Path home) {
            return home.resolve(".codeium/windsurf/mcp_config.json");
        }
    },

    CLINE(
        "cline",
        "Cline",
        "Cline VS Code extension settings",
        """
        {
          "mcpServers": {
            "condense": {
              "command": "condense",
              "args": ["mcp", "--start"]
            }
          }
        }
        """,
        null
    ) {
        @Override
        public Path resolveConfigPath(Path home) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac")) {
                return home.resolve("Library/Application Support/Code/User/globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json");
            }
            if (os.contains("win")) {
                String appData = System.getenv("APPDATA");
                if (appData != null && !appData.isBlank()) {
                    return Path.of(appData, "Code/User/globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json");
                }
                return home.resolve("AppData/Roaming/Code/User/globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json");
            }
            return home.resolve(".config/Code/User/globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json");
        }
    },

    ZED(
        "zed",
        "Zed",
        "Zed context server settings",
        """
        {
          "context_servers": {
            "condense": {
              "command": {
                "path": "condense",
                "args": ["mcp", "--start"]
              }
            }
          }
        }
        """,
        null
    ) {
        @Override
        public Path resolveConfigPath(Path home) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac")) {
                return home.resolve("Library/Application Support/Zed/settings.json");
            }
            return home.resolve(".config/zed/settings.json");
        }
    },

    VSCODE(
        "vscode",
        "VS Code / Copilot",
        "VS Code workspace MCP settings",
        """
        {
          "servers": [
            {
              "name": "condense",
              "command": "condense",
              "args": ["mcp", "--start"]
            }
          ]
        }
        """,
        null
    ) {
        @Override
        public Path resolveConfigPath(Path home) {
            return Path.of(".vscode/mcp.json");
        }
    },

    OPENCODE(
        "opencode",
        "OpenCode",
        "OpenCode configuration file",
        """
        {
          "mcp": {
            "servers": {
              "condense": {
                "command": "condense",
                "args": ["mcp", "--start"]
              }
            }
          }
        }
        """,
        null
    ) {
        @Override
        public Path resolveConfigPath(Path home) {
            return home.resolve(".config/opencode/opencode.json");
        }
    },

    ANTIGRAVITY(
        "antigravity",
        "Antigravity / Gemini CLI",
        "Antigravity MCP server configuration",
        """
        {
          "mcpServers": {
            "condense": {
              "command": "condense",
              "args": ["mcp", "--start"]
            }
          }
        }
        """,
        null
    ) {
        @Override
        public Path resolveConfigPath(Path home) {
            return home.resolve(".gemini/antigravity-cli/mcp_config.json");
        }
    },

    GENERIC(
        "generic",
        "Generic MCP Client",
        "Standard stdio MCP 2.0 configuration",
        """
        {
          "mcpServers": {
            "condense": {
              "command": "condense",
              "args": ["mcp", "--start"]
            }
          }
        }
        """,
        null
    ) {
        @Override
        public Path resolveConfigPath(Path home) {
            return null;
        }
    };

    private final String id;
    private final String displayName;
    private final String description;
    private final String snippet;
    private final String setupCommand;

    McpClient(String id, String displayName, String description, String snippet, String setupCommand) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.snippet = snippet.strip();
        this.setupCommand = setupCommand;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public String snippet() {
        return snippet;
    }

    public Optional<String> setupCommand() {
        return Optional.ofNullable(setupCommand);
    }

    public abstract Path resolveConfigPath(Path home);

    public static Optional<McpClient> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.equals("all")) {
            return Optional.empty();
        }
        if (normalized.equals("copilot") || normalized.equals("code")) {
            return Optional.of(VSCODE);
        }
        if (normalized.equals("gemini")) {
            return Optional.of(ANTIGRAVITY);
        }
        return Arrays.stream(values())
            .filter(c -> c.id.equals(normalized) || c.name().equalsIgnoreCase(normalized.replace('-', '_'))
                || c.displayName.toLowerCase(Locale.ROOT).contains(normalized))
            .findFirst();
    }
}
