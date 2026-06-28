package com.zapproxy.config;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.zapproxy.core.ConfigLoader;
import com.zapproxy.core.PlatformDirs;
import com.zapproxy.core.TeeMode;
import com.zapproxy.core.ZapConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

/**
 * Writes {@link ZapConfig} back to disk as TOML, atomically.
 *
 * <p>The atomic write pattern:
 * <ol>
 *   <li>Serialize config to a temp file in the same directory</li>
 *   <li>Atomic rename temp → final path (same filesystem, no partial writes)</li>
 * </ol>
 *
 * <p>Supports key-path mutation: {@link #set(String, String)} parses a dotted
 * key path (e.g. {@code "tee.mode"}) and updates the in-memory config before
 * writing.
 */
@ApplicationScoped
public class ConfigWriter {

    private static final Logger log = Logger.getLogger(ConfigWriter.class);
    private static final TomlMapper TOML = new TomlMapper();

    @Inject
    PlatformDirs platformDirs;

    @Inject
    ConfigLoader configLoader;

    /**
     * Sets a config key to a value and writes the result to disk.
     *
     * <p>Supported keys:
     * <ul>
     *   <li>{@code tee.enabled} — "true" or "false"</li>
     *   <li>{@code tee.mode} — "failures", "always", or "never"</li>
     *   <li>{@code hooks.exclude_commands} — comma-separated command list</li>
     * </ul>
     *
     * @param keyPath dotted key path, e.g. "tee.mode"
     * @param value   string value to set
     * @throws IOException if the config cannot be written
     * @throws IllegalArgumentException if the key is unknown
     */
    public void set(String keyPath, String value) throws IOException {
        ZapConfig current = configLoader.load();
        ZapConfig updated = applyMutation(current, keyPath, value);
        write(updated);
        configLoader.invalidateCache();
    }

    /**
     * Resets the config to defaults and writes to disk.
     */
    public void reset() throws IOException {
        write(ZapConfig.defaults());
        configLoader.invalidateCache();
    }

    /**
     * Returns the current config as a TOML string.
     */
    public String toTomlString() throws IOException {
        ZapConfig config = configLoader.load();
        return TOML.writeValueAsString(config);
    }

    /**
     * Returns the value of a single config key as a string.
     *
     * @param keyPath dotted key path, e.g. "tee.mode"
     * @throws IllegalArgumentException if the key is unknown
     */
    public String get(String keyPath) {
        ZapConfig config = configLoader.load();
        return switch (keyPath) {
            case "tee.enabled"              -> String.valueOf(config.tee().enabled());
            case "tee.mode"                 -> config.tee().mode().toValue();
            case "hooks.exclude_commands"   -> String.join(",", config.hooks().excludeCommands());
            default -> throw new IllegalArgumentException(
                "Unknown config key: '" + keyPath + "'. " +
                "Valid keys: tee.enabled, tee.mode, hooks.exclude_commands");
        };
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private ZapConfig applyMutation(ZapConfig config, String keyPath, String value) {
        return switch (keyPath) {
            case "tee.enabled" -> new ZapConfig(
                config.hooks(),
                new ZapConfig.TeeConfig(
                    Boolean.parseBoolean(value.trim()),
                    config.tee().mode()));

            case "tee.mode" -> new ZapConfig(
                config.hooks(),
                new ZapConfig.TeeConfig(
                    config.tee().enabled(),
                    TeeMode.fromString(value)));

            case "hooks.exclude_commands" -> {
                List<String> cmds = value.isBlank()
                    ? List.of()
                    : Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
                yield new ZapConfig(
                    new ZapConfig.HooksConfig(cmds),
                    config.tee());
            }

            default -> throw new IllegalArgumentException(
                "Unknown config key: '" + keyPath + "'. " +
                "Valid keys: tee.enabled, tee.mode, hooks.exclude_commands");
        };
    }

    private void write(ZapConfig config) throws IOException {
        Path configFile = platformDirs.getConfigFile();
        Files.createDirectories(configFile.getParent());

        // Write to temp file in the same directory, then atomically rename
        Path tmp = Files.createTempFile(configFile.getParent(), ".zap-config-", ".toml.tmp");
        try {
            TOML.writeValue(tmp.toFile(), config);
            Files.move(tmp, configFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
            log.debugf("Config written to %s", configFile);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
