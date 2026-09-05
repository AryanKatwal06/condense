package com.condense.read;

import com.condense.filter.pipeline.config.DefinitionError;
import com.condense.filter.pipeline.config.DefinitionMappers;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fail-closed catalog of builtin language rules.
 * Enumeration is {@code /languages/index.toml} — never a classpath directory walk.
 */
public final class LanguageDefinitionCatalog {

    public static final String INDEX_RESOURCE = "/languages/index.toml";
    public static final String DEFINITION_DIR = "/languages/";

    private final Map<String, CompiledLanguage> byName;
    private final Map<String, String> extensionOwners;
    private final Map<String, String> filenameOwners;

    private static final class Holder {
        private static final LanguageDefinitionCatalog INSTANCE = loadOrDie();
    }

    public static LanguageDefinitionCatalog standalone() {
        return Holder.INSTANCE;
    }

    LanguageDefinitionCatalog(
            Map<String, CompiledLanguage> byName,
            Map<String, String> extensionOwners,
            Map<String, String> filenameOwners
    ) {
        this.byName = Collections.unmodifiableMap(byName);
        this.extensionOwners = Collections.unmodifiableMap(extensionOwners);
        this.filenameOwners = Collections.unmodifiableMap(filenameOwners);
    }

    public CompiledLanguage required(String name) {
        CompiledLanguage language = byName.get(normalize(name));
        if (language == null) {
            throw new IllegalArgumentException("unknown language '" + name + "'");
        }
        return language;
    }

    public CompiledLanguage find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return byName.get(normalize(name));
    }

    public CompiledLanguage detect(Path file) {
        if (file == null) {
            return null;
        }
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        return detect(fileName);
    }

    public CompiledLanguage detect(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        String byFile = filenameOwners.get(lower);
        if (byFile != null) {
            return byName.get(byFile);
        }
        String bestExt = null;
        for (String ext : extensionOwners.keySet()) {
            if (lower.endsWith(ext) && (bestExt == null || ext.length() > bestExt.length())) {
                bestExt = ext;
            }
        }
        if (bestExt == null) {
            return null;
        }
        return byName.get(extensionOwners.get(bestExt));
    }

    public List<CompiledLanguage> all() {
        return List.copyOf(byName.values());
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    static LanguageDefinitionCatalog loadOrDie() {
        List<String> errors = new ArrayList<>();
        LanguageDefinitionCatalog catalog = load(errors);
        if (catalog == null || !errors.isEmpty()) {
            throw new IllegalStateException(
                "Builtin language definitions failed to load:\n" + String.join("\n", errors));
        }
        return catalog;
    }

    static LanguageDefinitionCatalog load(List<String> errors) {
        LanguageDefinition.Index index = readIndex(errors);
        if (index == null) {
            return null;
        }
        Map<String, CompiledLanguage> byName = new LinkedHashMap<>();
        Map<String, String> extensionOwners = new LinkedHashMap<>();
        Map<String, String> filenameOwners = new LinkedHashMap<>();
        for (String name : index.definitions()) {
            if (name == null || name.isBlank()) {
                errors.add(INDEX_RESOURCE + ": definitions contains a blank name");
                continue;
            }
            if (byName.containsKey(name)) {
                errors.add(INDEX_RESOURCE + ": duplicate definition name '" + name + "'");
                continue;
            }
            LanguageDefinition definition = readDefinition(name, errors);
            if (definition == null) {
                continue;
            }
            try {
                CompiledLanguage compiled = CompiledLanguage.compile(definition);
                byName.put(name, compiled);
                for (String ext : definition.extensions()) {
                    String key = normalizeExt(ext);
                    if (key.isEmpty()) {
                        errors.add(DEFINITION_DIR + name + ".toml: extensions contains a blank entry");
                        continue;
                    }
                    String previous = extensionOwners.put(key, name);
                    if (previous != null) {
                        errors.add("Duplicate extension '" + ext + "' in '" + previous + "' and '" + name + "'");
                    }
                }
                for (String file : definition.filenames()) {
                    String key = file == null ? "" : file.trim().toLowerCase(Locale.ROOT);
                    if (key.isBlank()) {
                        errors.add(DEFINITION_DIR + name + ".toml: filenames contains a blank entry");
                        continue;
                    }
                    String previous = filenameOwners.put(key, name);
                    if (previous != null) {
                        errors.add("Duplicate filename '" + file + "' in '" + previous + "' and '" + name + "'");
                    }
                }
            } catch (IllegalArgumentException e) {
                errors.add(DEFINITION_DIR + name + ".toml: " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            return null;
        }
        return new LanguageDefinitionCatalog(byName, extensionOwners, filenameOwners);
    }

    static LanguageDefinition.Index readIndex(List<String> errors) {
        byte[] bytes = readResource(INDEX_RESOURCE, errors);
        if (bytes == null) {
            return null;
        }
        try {
            LanguageDefinition.Index index = DefinitionMappers.STRICT_TOML.readValue(bytes, LanguageDefinition.Index.class);
            if (index.schemaVersion() == null || index.schemaVersion() != LanguageDefinition.SCHEMA_VERSION) {
                errors.add(new DefinitionError(
                    "schema_version",
                    null,
                    null,
                    "schema_version is required and must be " + LanguageDefinition.SCHEMA_VERSION
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

    static LanguageDefinition readDefinition(String name, List<String> errors) {
        String resource = DEFINITION_DIR + name + ".toml";
        byte[] bytes = readResource(resource, errors);
        if (bytes == null) {
            return null;
        }
        try {
            LanguageDefinition definition = DefinitionMappers.STRICT_TOML.readValue(bytes, LanguageDefinition.class);
            List<String> local = new ArrayList<>();
            if (definition.schemaVersion() == null
                || definition.schemaVersion() != LanguageDefinition.SCHEMA_VERSION) {
                local.add(new DefinitionError(
                    "schema_version",
                    null,
                    null,
                    "schema_version is required and must be " + LanguageDefinition.SCHEMA_VERSION
                ).format());
            }
            if (definition.name() == null || definition.name().isBlank()) {
                local.add(resource + ": 'name' is required");
            } else if (!name.equals(definition.name())) {
                local.add(resource + ": name '" + definition.name() + "' does not match file '" + name + "'");
            }
            LanguageFamily family = null;
            try {
                family = LanguageFamily.parse(definition.family());
            } catch (IllegalArgumentException e) {
                local.add(resource + ": " + e.getMessage());
            }
            try {
                RawStringStyle.parse(definition.rawStrings());
            } catch (IllegalArgumentException e) {
                local.add(resource + ": " + e.getMessage());
            }
            if (family == LanguageFamily.DATA) {
                if (definition.lineComment() != null || definition.blockCommentStart() != null
                    || definition.blockCommentEnd() != null) {
                    local.add(resource + ": data languages must not declare comment syntax");
                }
            }
            if (definition.extensions().isEmpty() && definition.filenames().isEmpty()) {
                local.add(resource + ": at least one extension or filename is required");
            }
            if (definition.tests().isEmpty()) {
                local.add(resource + ": at least one [[tests]] entry is required");
            } else {
                for (int i = 0; i < definition.tests().size(); i++) {
                    LanguageDefinition.InlineTest test = definition.tests().get(i);
                    if (test.id() == null || test.id().isBlank()) {
                        local.add(resource + ".tests[" + i + "]: 'id' is required");
                    }
                    if (test.input() == null) {
                        local.add(resource + ".tests[" + i + "]: 'input' is required");
                    }
                }
            }
            if (family != null) {
                CompiledLanguage.compileOutlines(definition, family, local);
            }
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

    private static byte[] readResource(String resource, List<String> errors) {
        try (InputStream in = LanguageDefinitionCatalog.class.getResourceAsStream(resource)) {
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

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeExt(String ext) {
        if (ext == null) {
            return "";
        }
        String key = ext.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return "";
        }
        return key.startsWith(".") ? key : "." + key;
    }
}
