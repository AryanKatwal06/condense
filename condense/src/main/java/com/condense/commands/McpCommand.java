package com.condense.commands;

import com.condense.core.TrackingRepository;
import com.condense.mcp.McpClient;
import com.condense.mcp.McpHandlers;
import com.condense.mcp.McpServer;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code condense mcp} — stdio JSON-RPC MCP server. Bare invocation prints
 * a client config snippet and exits 0; {@code --start} speaks the protocol;
 * {@code --client} outputs client-specific snippets and config paths.
 */
@Command(
    name = "mcp",
    description = "Model Context Protocol server over stdio.",
    mixinStandardHelpOptions = true
)
@Dependent
@Unremovable
public class McpCommand implements Callable<Integer> {

    @Option(names = {"--start"}, description = "Start the MCP server on stdin/stdout")
    public boolean start;

    @Option(
        names = {"-c", "--client"},
        description = "Print configuration snippet and path for a specific MCP client. " +
                      "Values: claude-desktop, claude-code, cursor, windsurf, cline, " +
                      "zed, vscode, opencode, antigravity, generic, all",
        paramLabel = "CLIENT"
    )
    public String client;

    @Option(names = {"--list-clients"}, description = "List all supported MCP clients and configuration locations")
    public boolean listClients;

    @Inject
    McpHandlers handlers;

    @Inject
    TrackingRepository tracking;

    public McpCommand() {}

    public McpCommand(McpHandlers handlers, TrackingRepository tracking) {
        this.handlers = handlers;
        this.tracking = tracking;
    }

    @Override
    public Integer call() {
        if (start) {
            return runServer();
        }
        if (listClients) {
            printClientList();
            return 0;
        }
        if (client != null && !client.isBlank()) {
            return printClientSnippet(client);
        }
        printDefaultSnippet();
        return 0;
    }

    private Integer runServer() {
        try {
            new McpServer(handlers).serve(System.in, System.out);
            return 0;
        } catch (Exception e) {
            System.err.println("condense mcp: " + e.getMessage());
            return 1;
        } finally {
            if (tracking != null) {
                tracking.close();
            }
        }
    }

    private void printDefaultSnippet() {
        System.out.println("Condense MCP Server");
        System.out.println("===================");
        System.out.println("Tools: run, explain, read, discover, propose");
        System.out.println("Resources: condense://gain, condense://gain/trend, condense://doctor");
        System.out.println();
        System.out.println("Supported clients: claude-desktop, claude-code, cursor, windsurf,");
        System.out.println("                   cline, zed, vscode, opencode, antigravity, generic");
        System.out.println("Run 'condense mcp --client <client>' for client-specific paths and syntax.");
        System.out.println();
        System.out.println("Standard configuration (Claude Desktop, Cursor, Windsurf, Cline):");
        System.out.println(McpClient.CLAUDE_DESKTOP.snippet());
        System.out.println();
        System.out.println("Start the server with: condense mcp --start");
        System.out.println("See docs/mcp.md for the tool and resource contracts.");
    }

    private void printClientList() {
        System.out.println("Supported MCP Clients");
        System.out.println("=====================");
        for (McpClient c : McpClient.values()) {
            System.out.printf("  %-16s %-20s (%s)%n", c.id(), c.displayName(), c.description());
        }
        System.out.println();
        System.out.println("Use 'condense mcp --client <name>' to view the exact snippet and path.");
    }

    private int printClientSnippet(String target) {
        Path home = resolveHome();
        if (target.equalsIgnoreCase("all")) {
            System.out.println("Condense MCP Client Configurations");
            System.out.println("==================================");
            for (McpClient c : McpClient.values()) {
                printSingleClient(c, home);
                System.out.println();
            }
            return 0;
        }

        Optional<McpClient> matched = McpClient.parse(target);
        if (matched.isEmpty()) {
            System.err.println("condense mcp: unknown client '" + target + "'");
            System.err.println("Valid clients: " + String.join(", ",
                Arrays.stream(McpClient.values()).map(McpClient::id).toList()) + ", all");
            return 1;
        }

        printSingleClient(matched.get(), home);
        return 0;
    }

    private void printSingleClient(McpClient c, Path home) {
        System.out.println("Client: " + c.displayName() + " (" + c.id() + ")");
        Path path = c.resolveConfigPath(home);
        if (path != null) {
            System.out.println("Config path: " + path);
        } else {
            System.out.println("Config: " + c.description());
        }
        c.setupCommand().ifPresent(cmd -> System.out.println("Setup command: " + cmd));
        System.out.println("Snippet:");
        System.out.println(c.snippet());
    }

    private static Path resolveHome() {
        String testHome = System.getProperty("condense.test.home");
        if (testHome != null && !testHome.isBlank()) {
            return Path.of(testHome);
        }
        String envTest = System.getenv("CONDENSE_TEST_HOME");
        if (envTest != null && !envTest.isBlank()) {
            return Path.of(envTest);
        }
        return Path.of(System.getProperty("user.home", "."));
    }
}
