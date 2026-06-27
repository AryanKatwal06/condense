package com.zapproxy.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Root configuration record for Zap.
 *
 * <p>Loaded from {@code ~/.config/zap/config.toml} (Linux),
 * {@code ~/Library/Application Support/zap/config.toml} (macOS), or
 * {@code %APPDATA%\zap\config.toml} (Windows).
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
public record ZapConfig(

    @JsonProperty("hooks")
    HooksConfig hooks,

    @JsonProperty("tee")
    TeeConfig tee

) {

    /** Returns a config with sensible production defaults. */
    public static ZapConfig defaults() {
        return new ZapConfig(
            new HooksConfig(List.of()),
            new TeeConfig(true, TeeMode.FAILURES)
        );
    }

    /**
     * Configuration for the hook installer.
     *
     * @param excludeCommands commands that should NOT be rewritten through zap,
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
}
