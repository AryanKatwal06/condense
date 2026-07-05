package com.condense.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Root configuration record for Condense.
 *
 * <p>Loaded from {@code ~/.config/condense/config.toml} (Linux),
 * {@code ~/Library/Application Support/condense/config.toml} (macOS), or
 * {@code %APPDATA%\condense\config.toml} (Windows).
 *
 * <p>Example config.toml:
 * <pre>
 * [hooks]
 * exclude_commands = ["curl", "playwright"]
 *
 * [tee]
 * enabled = true
 * mode = "failures"
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CondenseConfig(

    @JsonProperty("hooks")
    HooksConfig hooks,

    @JsonProperty("tee")
    TeeConfig tee,

    @JsonProperty("commands")
    java.util.Map<String, CommandConfig> commands

) {

    /** Returns a config with sensible production defaults. */
    public static CondenseConfig defaults() {
        return new CondenseConfig(
            new HooksConfig(List.of()),
            new TeeConfig(true, TeeMode.FAILURES),
            java.util.Map.of()
        );
    }

    public CommandConfig commandConfig(String command) {
        if (commands == null) return new CommandConfig();
        return commands.getOrDefault(command.toLowerCase().replace(' ', '-'),
               commands.getOrDefault(command.toLowerCase(),
               new CommandConfig()));
    }

    /**
     * Configuration for the hook installer.
     *
     * @param excludeCommands commands that should NOT be rewritten through condense,
     *                        even when the hook is active (e.g. "curl", "playwright")
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HooksConfig(

        @JsonProperty("exclude_commands")
        List<String> excludeCommands

    ) {
        /** No-arg constructor for Jackson deserialization. */
        public HooksConfig() {
            this(List.of());
        }

        @Override
        public List<String> excludeCommands() {
            return excludeCommands != null ? excludeCommands : List.of();
        }
    }

    /**
     * Configuration for the tee (raw output dump) system.
     *
     * @param enabled whether the tee system is active at all
     * @param mode    when to save raw output ({@link TeeMode})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeeConfig(

        @JsonProperty("enabled")
        boolean enabled,

        @JsonProperty("mode")
        TeeMode mode

    ) {
        /** No-arg constructor for Jackson deserialization. */
        public TeeConfig() {
            this(true, TeeMode.FAILURES);
        }

        @Override
        public TeeMode mode() {
            return mode != null ? mode : TeeMode.FAILURES;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommandConfig(

        @JsonProperty("max_failures")
        Integer maxFailures,

        @JsonProperty("show_timing")
        Boolean showTiming,

        @JsonProperty("max_lines")
        Integer maxLines

    ) {
        public CommandConfig() { this(null, null, null); }

        public int maxFailures(int defaultValue) {
            return maxFailures != null ? maxFailures : defaultValue;
        }
        public boolean showTiming(boolean defaultValue) {
            return showTiming != null ? showTiming : defaultValue;
        }
        public int maxLines(int defaultValue) {
            return maxLines != null ? maxLines : defaultValue;
        }
    }
}
