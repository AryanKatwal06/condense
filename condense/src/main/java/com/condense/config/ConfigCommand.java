package com.condense.config;

import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code condense config} — reads and writes Condense configuration.
 *
 * <p>Usage:
 * <pre>
 * condense config --list                                # print full config as TOML
 * condense config --get tee.mode                        # print single key value
 * condense config --set tee.mode=always                 # update a key
 * condense config --set hooks.exclude_commands=curl,playwright
 * condense config --reset                               # restore defaults
 * </pre>
 */
@Command(
    name = "config",
    description = "Read and write Condense configuration.",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "Config file location:",
        "  Linux:   $XDG_CONFIG_HOME/condense/config.toml  (default: ~/.config/condense/config.toml)",
        "  macOS:   ~/Library/Application Support/condense/config.toml",
        "  Windows: %APPDATA%\\condense\\config.toml",
        "",
        "Valid keys:",
        "  tee.enabled              true | false",
        "  tee.mode                 failures | always | never",
        "  hooks.exclude_commands   comma-separated command list",
        ""
    }
)
public class ConfigCommand implements Runnable {

    @Option(names = "--list",
        description = "Print the full config file as TOML.")
    boolean list;

    @Option(names = "--get",
        description = "Print the value of a single key (e.g. tee.mode).",
        paramLabel = "KEY")
    String getKey;

    @Option(names = "--set",
        description = "Set a key=value pair (e.g. tee.mode=always).",
        paramLabel = "KEY=VALUE")
    String setKeyValue;

    @Option(names = "--reset",
        description = "Reset config to defaults.")
    boolean reset;

    @Inject
    ConfigWriter configWriter;

    @Override
    public void run() {
        try {
            if (list) {
                System.out.println(configWriter.toTomlString());
                return;
            }

            if (getKey != null) {
                System.out.println(configWriter.get(getKey));
                return;
            }

            if (setKeyValue != null) {
                int eq = setKeyValue.indexOf('=');
                if (eq <= 0) {
                    System.err.println("condense config --set: expected KEY=VALUE, got: " + setKeyValue);
                    return;
                }
                String key   = setKeyValue.substring(0, eq).trim();
                String value = setKeyValue.substring(eq + 1).trim();
                configWriter.set(key, value);
                System.out.println("✓ " + key + " = " + value);
                return;
            }

            if (reset) {
                configWriter.reset();
                System.out.println("✓ Config reset to defaults.");
                return;
            }

            // No flag: print help
            System.out.println("Usage: condense config --list | --get KEY | --set KEY=VALUE | --reset");
            System.out.println("Run 'condense config --help' for full details.");

        } catch (IllegalArgumentException e) {
            System.err.println("condense config: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("condense config: unexpected error: " + e.getMessage());
        }
    }
}
