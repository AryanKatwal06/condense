package com.condense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CliPreParser {

    private static final Set<String> SUBCOMMANDS = Set.of(
        "gain",
        "doctor",
        "discover",
        "propose",
        "explain",
        "read",
        "init",
        "config",
        "completion",
        "update",
        "mcp",
        "uninstall",
        "session",
        "report"
    );

    public record PreParseResult(
        List<String> condenseArgs,
        List<String> childArgs,
        boolean isSubcommand
    ) {}

    private CliPreParser() {}

    public static PreParseResult parse(String... args) {
        if (args == null || args.length == 0) {
            return new PreParseResult(List.of(), List.of(), false);
        }

        // Check if there is an explicit "--" delimiter
        int delimiterIndex = -1;
        for (int i = 0; i < args.length; i++) {
            if ("--".equals(args[i])) {
                delimiterIndex = i;
                break;
            }
        }

        // If no delimiter was provided, check if any token is a recognized subcommand
        if (delimiterIndex == -1) {
            for (String arg : args) {
                if (arg != null && !arg.startsWith("-") && SUBCOMMANDS.contains(arg.toLowerCase(Locale.ROOT))) {
                    return new PreParseResult(List.of(args), List.of(), true);
                }
            }
        }

        List<String> condenseArgs = new ArrayList<>();
        List<String> childArgs = new ArrayList<>();

        int i = 0;
        while (i < args.length) {
            String arg = args[i];

            if ("--".equals(arg)) {
                // Everything strictly after "--" is treated as child command arguments
                i++;
                while (i < args.length) {
                    childArgs.add(args[i]);
                    i++;
                }
                break;
            }

            if (isCondenseFlag(arg)) {
                condenseArgs.add(arg);
                if (requiresArgument(arg) && i + 1 < args.length
                        && !args[i + 1].startsWith("-") && !"--".equals(args[i + 1])) {
                    condenseArgs.add(args[i + 1]);
                    i++;
                }
                i++;
            } else {
                // Encountered first non-flag argument (before any "--").
                // All remaining tokens belong to the child command.
                while (i < args.length) {
                    childArgs.add(args[i]);
                    i++;
                }
                break;
            }
        }

        return new PreParseResult(condenseArgs, childArgs, false);
    }

    private static boolean isCondenseFlag(String arg) {
        if (arg == null || !arg.startsWith("-")) {
            return false;
        }
        if (arg.equals("-v") || arg.startsWith("-v") || arg.equals("--verbose")) {
            return true;
        }
        if (arg.equals("-u") || arg.equals("--ultra-compact")) {
            return true;
        }
        if (arg.equals("--format") || arg.startsWith("--format=")) {
            return true;
        }
        if (arg.equals("--plain") || arg.equals("--ascii")) {
            return true;
        }
        if (arg.equals("-h") || arg.equals("--help") || arg.equals("-V") || arg.equals("--version")) {
            return true;
        }
        return false;
    }

    private static boolean requiresArgument(String arg) {
        return "--format".equals(arg);
    }
}
