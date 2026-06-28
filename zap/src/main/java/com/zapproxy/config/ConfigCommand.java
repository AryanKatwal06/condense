package com.zapproxy.config;

import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code zap config} — reads and writes Zap configuration.
 *
 * <p>Usage:
 * <pre>
 * zap config --list                                # print full config as TOML
 * zap config --get tee.mode                        # print single key value
 * zap config --set tee.mode=always                 # update a key
 * zap config --set hooks.exclude_commands=curl,playwright
 * zap config --reset                               # restore defaults
 * </pre>
 */
@Command(
    name = "config",
    description = "Read and write Zap configuration.",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "Config file location:",
        "  Linux:   $XDG_CONFIG_HOME/zap/config.toml  (default: ~/.config/zap/config.toml)",
        "  macOS:   ~/Library/Application Support/zap/config.toml",
        "  Windows: %APPDATA%\\zap\\config.toml",
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
                    System.err.println("zap config --set: expected KEY=VALUE, got: " + setKeyValue);
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
            System.out.println("Usage: zap config --list | --get KEY | --set KEY=VALUE | --reset");
            System.out.println("Run 'zap config --help' for full details.");

        } catch (IllegalArgumentException e) {
            System.err.println("zap config: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("zap config: unexpected error: " + e.getMessage());
        }
    }
}
