package com.condense.filter.pipeline.config;

import com.condense.filter.pipeline.FilterPipeline;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed catalog of builtin filter definitions.
 * Enumeration is {@code /filters/index.toml} — never a classpath directory walk.
 */
public final class BuiltinDefinitionCatalog {

    public static final String INDEX_RESOURCE = "/filters/index.toml";
    public static final String DEFINITION_DIR = "/filters/";

    private static final Set<String> SELECT_INPUTS = Set.of(
        BuiltinDefinition.SELECT_STDOUT_OR_STDERR,
        BuiltinDefinition.SELECT_STDERR_THEN_STDOUT,
        BuiltinDefinition.SELECT_STDOUT,
        BuiltinDefinition.SELECT_STDERR
    );

    private final Map<String, BuiltinDefinition> byName;
    private final Map<String, FilterPipeline> pipelines;
    private final Map<String, String> commandOwners;

    private static final class Holder {
        private static final BuiltinDefinitionCatalog INSTANCE = loadOrDie();
    }

    public static BuiltinDefinitionCatalog standalone() {
        return Holder.INSTANCE;
    }

    BuiltinDefinitionCatalog(
            Map<String, BuiltinDefinition> byName,
            Map<String, FilterPipeline> pipelines,
            Map<String, String> commandOwners) {
        this.byName = Collections.unmodifiableMap(byName);
        this.pipelines = Collections.unmodifiableMap(pipelines);
        this.commandOwners = Collections.unmodifiableMap(commandOwners);
    }

    public FilterPipeline requiredPipeline(String name) {
        FilterPipeline pipeline = pipelines.get(name);
        if (pipeline == null) {
            throw new IllegalStateException("Missing builtin filter definition: " + name);
        }
        return pipeline;
    }

    public BuiltinDefinition requiredDefinition(String name) {
        BuiltinDefinition definition = byName.get(name);
        if (definition == null) {
            throw new IllegalStateException("Missing builtin filter definition: " + name);
        }
        return definition;
    }

    public List<BuiltinDefinition> all() {
        return List.copyOf(byName.values());
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    /**
     * Longest-prefix match over builtin {@code commands}. Null when unmatched.
     */
    public BuiltinDefinition findByCommand(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String[] tokens = command.trim().toLowerCase(Locale.ROOT).split("\\s+");
        for (int len = tokens.length; len >= 1; len--) {
            String prefix = String.join(" ", Arrays.copyOfRange(tokens, 0, len));
            String name = commandOwners.get(prefix);
            if (name != null) {
                return byName.get(name);
            }
        }
        return null;
    }

    static BuiltinDefinitionCatalog loadOrDie() {
        List<String> errors = new ArrayList<>();
        BuiltinDefinitionCatalog catalog = load(errors);
        if (catalog == null || !errors.isEmpty()) {
            throw new IllegalStateException(
                "Builtin filter definitions failed to load:\n" + String.join("\n", errors));
        }
        return catalog;
    }

    static BuiltinDefinitionCatalog load(List<String> errors) {
        BuiltinDefinition.Index index = readIndex(errors);
        if (index == null) {
            return null;
        }
        Map<String, BuiltinDefinition> byName = new LinkedHashMap<>();
        Map<String, FilterPipeline> pipelines = new LinkedHashMap<>();
        Map<String, String> commandOwners = new LinkedHashMap<>();
        for (String name : index.definitions()) {
            if (name == null || name.isBlank()) {
                errors.add(INDEX_RESOURCE + ": definitions contains a blank name");
                continue;
            }
            if (byName.containsKey(name)) {
                errors.add(INDEX_RESOURCE + ": duplicate definition name '" + name + "'");
                continue;
            }
            BuiltinDefinition definition = readDefinition(name, errors);
            if (definition == null) {
                continue;
            }
            byName.put(name, definition);
            for (String command : definition.commands()) {
                String key = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
                if (key.isBlank()) {
                    errors.add(DEFINITION_DIR + name + ".toml: commands contains a blank entry");
                    continue;
                }
                String previous = commandOwners.put(key, name);
                if (previous != null) {
                    errors.add("Duplicate command '" + command + "' in '" + previous + "' and '" + name + "'");
                }
            }
            List<String> stageErrors = new ArrayList<>();
            List<FilterOverrideConfig.StageDef> stages = definition.stages();
            for (int i = 0; i < stages.size(); i++) {
                StageFactory.validate("[" + name + ".stages[" + i + "]]", stages.get(i), stageErrors);
            }
            errors.addAll(stageErrors);
            if (stageErrors.isEmpty()) {
                pipelines.put(name, StageFactory.buildPipeline(stages));
            }
        }
        if (!errors.isEmpty()) {
            return null;
        }
        return new BuiltinDefinitionCatalog(byName, pipelines, commandOwners);
    }

    private static void validateSelectInput(String resource, String selectInput, List<String> errors) {
        if (selectInput == null || selectInput.isBlank()) {
            return;
        }
        String key = selectInput.trim().toLowerCase(Locale.ROOT);
        if (!SELECT_INPUTS.contains(key)) {
            errors.add(resource + ": select_input '" + selectInput + "' must be one of "
                + SELECT_INPUTS);
        }
    }

    private static void validateGate(String resource, BuiltinDefinition.Gate gate, List<String> errors) {
        if (gate == null) {
            return;
        }
        if (gate.passthroughVerbose() != null && gate.passthroughVerbose() < 0) {
            errors.add(resource + ": gate.passthrough_verbose must be >= 0");
        }
        if (gate.passthroughMaxLines() != null && gate.passthroughMaxLines() < 0) {
            errors.add(resource + ": gate.passthrough_max_lines must be >= 0");
        }
    }

    static BuiltinDefinition.Index readIndex(List<String> errors) {
        byte[] bytes = readResource(INDEX_RESOURCE, errors);
        if (bytes == null) {
            return null;
        }
        try {
            BuiltinDefinition.Index index = DefinitionMappers.STRICT_TOML.readValue(bytes, BuiltinDefinition.Index.class);
            if (index.schemaVersion() == null || index.schemaVersion() != FilterOverrideConfig.SCHEMA_VERSION) {
                errors.add(new DefinitionError(
                    "schema_version",
                    null,
                    null,
                    "schema_version is required and must be " + FilterOverrideConfig.SCHEMA_VERSION
                ).format());
                return null;
            }
            if (index.definitions().isEmpty()) {
                errors.add(INDEX_RESOURCE + ": definitions must not be empty");
                return null;
            }
            return index;
        } catch (UnrecognizedPropertyException e) {
            errors.add(FilterOverrideLoader.unknownKeyError(e, new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).format());
            return null;
        } catch (Exception e) {
            errors.add(FilterOverrideLoader.formatParseError(INDEX_RESOURCE, e));
            return null;
        }
    }

    static BuiltinDefinition readDefinition(String name, List<String> errors) {
        String resource = DEFINITION_DIR + name + ".toml";
        byte[] bytes = readResource(resource, errors);
        if (bytes == null) {
            return null;
        }
        try {
            BuiltinDefinition definition = DefinitionMappers.STRICT_TOML.readValue(bytes, BuiltinDefinition.class);
            List<String> local = new ArrayList<>();
            if (definition.schemaVersion() == null
                || definition.schemaVersion() != FilterOverrideConfig.SCHEMA_VERSION) {
                local.add(new DefinitionError(
                    "schema_version",
                    null,
                    null,
                    "schema_version is required and must be " + FilterOverrideConfig.SCHEMA_VERSION
                ).format());
            }
            if (definition.name() == null || definition.name().isBlank()) {
                local.add(resource + ": 'name' is required");
            } else if (!name.equals(definition.name())) {
                local.add(resource + ": name '" + definition.name() + "' does not match file '" + name + "'");
            }
            if (definition.commands().isEmpty()) {
                local.add(resource + ": 'commands' must not be empty");
            }
            if (definition.tests().isEmpty()) {
                local.add(resource + ": at least one [[tests]] entry is required");
            } else {
                for (int i = 0; i < definition.tests().size(); i++) {
                    BuiltinDefinition.InlineTest test = definition.tests().get(i);
                    if (test.id() == null || test.id().isBlank()) {
                        local.add(resource + ".tests[" + i + "]: 'id' is required");
                    }
                    if (test.expected() == null) {
                        local.add(resource + ".tests[" + i + "]: 'expected' is required");
                    }
                }
            }
            validateSelectInput(resource, definition.selectInput(), local);
            validateGate(resource, definition.gate(), local);
            errors.addAll(local);
            return local.isEmpty() ? definition : null;
        } catch (UnrecognizedPropertyException e) {
            errors.add(resource + ": " + FilterOverrideLoader.unknownKeyError(
                e, new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).format());
            return null;
        } catch (Exception e) {
            errors.add(FilterOverrideLoader.formatParseError(resource, e));
            return null;
        }
    }

    private static byte[] readResource(String resource, List<String> errors) {
        try (InputStream in = BuiltinDefinitionCatalog.class.getResourceAsStream(resource)) {
            if (in == null) {
                errors.add("Missing classpath resource " + resource);
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            errors.add("Cannot read " + resource + ": " + e.getMessage());
            return null;
        }
    }
}
