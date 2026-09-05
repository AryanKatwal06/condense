package com.condense.discover;

import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import com.condense.filter.pipeline.config.DefinitionError;
import com.condense.filter.pipeline.config.DefinitionMappers;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed catalog of builtin discovery rules.
 * Enumeration is {@code /discover/index.toml} — never a classpath directory walk.
 */
public final class DiscoverRuleCatalog {

    public static final String INDEX_RESOURCE = "/discover/index.toml";
    public static final String DEFINITION_DIR = "/discover/";

    private final Map<String, DiscoverDefinition> byName;
    private final List<DiscoverDefinition> ordered;

    private static final class Holder {
        private static final DiscoverRuleCatalog INSTANCE = loadOrDie();
    }

    public static DiscoverRuleCatalog standalone() {
        return Holder.INSTANCE;
    }

    DiscoverRuleCatalog(Map<String, DiscoverDefinition> byName) {
        this.byName = Collections.unmodifiableMap(byName);
        List<DiscoverDefinition> sorted = new ArrayList<>(byName.values());
        sorted.sort(Comparator
            .comparing(DiscoverDefinition::priority, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(rule -> rule.name() == null ? "" : rule.name()));
        this.ordered = List.copyOf(sorted);
    }

    public List<DiscoverDefinition> rules() {
        return ordered;
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    public DiscoverDefinition required(String name) {
        DiscoverDefinition definition = byName.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("unknown discover rule '" + name + "'");
        }
        return definition;
    }

    static DiscoverRuleCatalog loadOrDie() {
        List<String> errors = new ArrayList<>();
        DiscoverRuleCatalog catalog = load(errors);
        if (catalog == null || !errors.isEmpty()) {
            throw new IllegalStateException(
                "Builtin discover definitions failed to load:\n" + String.join("\n", errors));
        }
        return catalog;
    }

    static DiscoverRuleCatalog load(List<String> errors) {
        DiscoverDefinition.Index index = readIndex(errors);
        if (index == null) {
            return null;
        }
        Map<String, DiscoverDefinition> byName = new LinkedHashMap<>();
        Map<String, Set<Integer>> prioritiesByFamily = new LinkedHashMap<>();
        Set<String> filterNames = new HashSet<>(BuiltinDefinitionCatalog.standalone().names());
        for (String name : index.definitions()) {
            if (name == null || name.isBlank()) {
                errors.add(INDEX_RESOURCE + ": definitions contains a blank name");
                continue;
            }
            if (byName.containsKey(name)) {
                errors.add(INDEX_RESOURCE + ": duplicate definition name '" + name + "'");
                continue;
            }
            DiscoverDefinition definition = readDefinition(name, filterNames, prioritiesByFamily, errors);
            if (definition != null) {
                byName.put(name, definition);
            }
        }
        if (!errors.isEmpty()) {
            return null;
        }
        return new DiscoverRuleCatalog(byName);
    }

    static DiscoverDefinition.Index readIndex(List<String> errors) {
        byte[] bytes = readResource(INDEX_RESOURCE, errors);
        if (bytes == null) {
            return null;
        }
        try {
            DiscoverDefinition.Index index = DefinitionMappers.STRICT_TOML.readValue(
                bytes, DiscoverDefinition.Index.class);
            if (index.schemaVersion() == null || index.schemaVersion() != DiscoverDefinition.SCHEMA_VERSION) {
                errors.add(new DefinitionError(
                    "schema_version",
                    null,
                    null,
                    "schema_version is required and must be " + DiscoverDefinition.SCHEMA_VERSION
                ).format());
                return null;
            }
            if (index.definitions().isEmpty()) {
                errors.add(INDEX_RESOURCE + ": definitions must not be empty");
                return null;
            }
            return index;
        } catch (UnrecognizedPropertyException e) {
            errors.add(FilterOverrideLoader.unknownKeyError(
                e, new String(bytes, StandardCharsets.UTF_8)).format());
            return null;
        } catch (Exception e) {
            errors.add(FilterOverrideLoader.formatParseError(INDEX_RESOURCE, e));
            return null;
        }
    }

    static DiscoverDefinition readDefinition(
            String name,
            Set<String> filterNames,
            Map<String, Set<Integer>> prioritiesByFamily,
            List<String> errors
    ) {
        String resource = DEFINITION_DIR + name + ".toml";
        byte[] bytes = readResource(resource, errors);
        if (bytes == null) {
            return null;
        }
        try {
            DiscoverDefinition definition = DefinitionMappers.STRICT_TOML.readValue(
                bytes, DiscoverDefinition.class);
            List<String> local = new ArrayList<>();
            validateDefinition(resource, name, definition, filterNames, prioritiesByFamily, local);
            errors.addAll(local);
            return local.isEmpty() ? definition : null;
        } catch (UnrecognizedPropertyException e) {
            errors.add(resource + ": " + FilterOverrideLoader.unknownKeyError(
                e, new String(bytes, StandardCharsets.UTF_8)).format());
            return null;
        } catch (Exception e) {
            errors.add(FilterOverrideLoader.formatParseError(resource, e));
            return null;
        }
    }

    static void validateDefinition(
            String resource,
            String expectedName,
            DiscoverDefinition definition,
            Set<String> filterNames,
            Map<String, Set<Integer>> prioritiesByFamily,
            List<String> errors
    ) {
        if (definition.schemaVersion() == null
            || definition.schemaVersion() != DiscoverDefinition.SCHEMA_VERSION) {
            errors.add(new DefinitionError(
                "schema_version",
                null,
                null,
                "schema_version is required and must be " + DiscoverDefinition.SCHEMA_VERSION
            ).format());
        }
        if (definition.name() == null || definition.name().isBlank()) {
            errors.add(resource + ": 'name' is required");
        } else if (expectedName != null && !expectedName.equals(definition.name())) {
            errors.add(resource + ": name '" + definition.name() + "' does not match file '" + expectedName + "'");
        }
        if (definition.family() == null || definition.family().isBlank()) {
            errors.add(resource + ": 'family' is required");
        }
        if (definition.priority() == null) {
            errors.add(resource + ": 'priority' is required");
        } else if (definition.family() != null && !definition.family().isBlank()) {
            Set<Integer> used = prioritiesByFamily.computeIfAbsent(
                definition.family(), key -> new HashSet<>());
            if (!used.add(definition.priority())) {
                errors.add(resource + ": priority " + definition.priority()
                    + " is already used in family '" + definition.family() + "'");
            }
        }
        if (definition.recommend().isEmpty()) {
            errors.add(resource + ": 'recommend' must not be empty");
        } else {
            for (String rec : definition.recommend()) {
                if (rec == null || rec.isBlank()) {
                    errors.add(resource + ": recommend contains a blank name");
                } else if (!filterNames.contains(rec)) {
                    errors.add(resource + ": recommend '" + rec + "' is not a filters/index.toml definition");
                }
            }
        }
        boolean hasSignals = !definition.signals().isEmpty() || !definition.extras().isEmpty()
            || definition.workspaceGitMarker();
        if (!hasSignals) {
            errors.add(resource + ": at least one signal, extra, or workspace_git is required");
        }
        for (int i = 0; i < definition.signals().size(); i++) {
            validateRelativePath(resource + ".signals[" + i + "]", definition.signals().get(i), errors);
        }
        for (int i = 0; i < definition.extras().size(); i++) {
            DiscoverDefinition.Extra extra = definition.extras().get(i);
            validateRelativePath(resource + ".extras[" + i + "].path", extra.path(), errors);
        }
    }

    static void validateRelativePath(String location, String path, List<String> errors) {
        if (path == null || path.isBlank()) {
            errors.add(location + ": path is required");
            return;
        }
        if (path.contains("\\") || path.contains("*") || path.contains("**") || path.contains("..")
            || path.startsWith("/") || path.indexOf(':') >= 0) {
            errors.add(location + ": path must be an exact relative file (no '*', '**', '..', or absolute)");
        }
    }

    private static byte[] readResource(String resource, List<String> errors) {
        try (InputStream in = DiscoverRuleCatalog.class.getResourceAsStream(resource)) {
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

    static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
