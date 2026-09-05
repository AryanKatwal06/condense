package com.condense.commands;

import com.condense.core.TrackingRepository;
import com.condense.mcp.McpHandlers;
import com.condense.mcp.McpServer;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code condense mcp} — stdio JSON-RPC MCP server. Bare invocation prints
 * a client config snippet and exits 0; {@code --start} speaks the protocol.
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
    boolean start;

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
        if (!start) {
            printSnippet();
            return 0;
        }
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

    private static void printSnippet() {
        System.out.println("Condense MCP Server");
        System.out.println("===================");
        System.out.println("Tools: run, explain, read");
        System.out.println("Resources: condense://gain, condense://doctor");
        System.out.println();
        System.out.println("To use in Claude Desktop or other MCP clients, add to your config:");
        System.out.println("{");
        System.out.println("  \"mcpServers\": {");
        System.out.println("    \"condense\": {");
        System.out.println("      \"command\": \"condense\",");
        System.out.println("      \"args\": [\"mcp\", \"--start\"]");
        System.out.println("    }");
        System.out.println("  }");
        System.out.println("}");
        System.out.println();
        System.out.println("Start the server with: condense mcp --start");
        System.out.println("See docs/mcp.md for the tool and resource contracts.");
    }
}
