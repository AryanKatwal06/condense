package com.condense.corpus;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.FilterStrategy;
import com.condense.core.PassthroughStrategy;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Test-only golden corpus catalog. Not shipped in the native image.
 */
public final class CorpusCatalog {

    public static final int SCHEMA_VERSION = 1;
    public static final String RESOURCE = "/corpus/catalog.json";
    public static final long FUZZ_SEED = 20260904L;
    public static final int FUZZ_ITERATIONS_PER_ENTRY = 25;

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private CorpusCatalog() {}

    public static Catalog load() throws Exception {
        return load(RESOURCE);
    }

    public static Catalog load(String resource) throws Exception {
        try (InputStream in = CorpusCatalog.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Corpus catalog not on classpath: " + resource);
            }
            Catalog catalog = MAPPER.readValue(in, Catalog.class);
            catalog.validate();
            return catalog;
        }
    }

    public static Catalog parse(String json) throws Exception {
        Catalog catalog = MAPPER.readValue(json, Catalog.class);
        catalog.validate();
        return catalog;
    }

    public static Set<Class<?>> discoverDomainFilters() throws Exception {
        URI location = FilterStrategy.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path root = Path.of(location);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("FilterStrategy must resolve to a classes directory, not a JAR: " + root);
        }
        Set<Class<?>> types = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                .forEach(p -> considerClass(root, p, types));
        }
        return types;
    }

    public static Map<String, Class<?>> prefixIndex() throws Exception {
        Map<String, Class<?>> prefixes = new LinkedHashMap<>();
        for (Class<?> type : discoverDomainFilters()) {
            for (String prefix : commandPrefixes(type)) {
                prefixes.put(prefix, type);
            }
        }
        return prefixes;
    }

    public static Class<?> resolveFilterClass(String command) throws Exception {
        return resolveFilterClass(command, prefixIndex());
    }

    public static Class<?> resolveFilterClass(String command, Map<String, Class<?>> prefixes) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        String[] tokens = command.trim().toLowerCase(Locale.ROOT).split("\\s+");
        for (int len = tokens.length; len >= 1; len--) {
            String prefix = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, len));
            Class<?> match = prefixes.get(prefix);
            if (match != null) {
                return match;
            }
        }
        throw new IllegalArgumentException("No FilterStrategy registered for command '" + command + "'");
    }

    public static FilterStrategy instantiate(Class<?> type) {
        try {
            return (FilterStrategy) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot construct " + type.getName() + " with a no-arg constructor", e);
        }
    }

    public static List<String> commandPrefixes(Class<?> type) {
        List<String> prefixes = new ArrayList<>();
        CommandFilter single = type.getAnnotation(CommandFilter.class);
        if (single != null) {
            prefixes.add(single.value().trim().toLowerCase(Locale.ROOT));
        }
        CommandFilters multi = type.getAnnotation(CommandFilters.class);
        if (multi != null) {
            for (CommandFilter filter : multi.value()) {
                prefixes.add(filter.value().trim().toLowerCase(Locale.ROOT));
            }
        }
        prefixes.removeIf(String::isBlank);
        return prefixes;
    }

    private static void considerClass(Path root, Path classFile, Set<Class<?>> types) {
        String relative = root.relativize(classFile).toString();
        String className = relative.substring(0, relative.length() - ".class".length())
            .replace('/', '.')
            .replace('\\', '.');
        if (!className.startsWith("com.condense.") || className.contains("package-info")) {
            return;
        }
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException | LinkageError e) {
            return;
        }
        if (type.isInterface() || type.isEnum() || Modifier.isAbstract(type.getModifiers())) {
            return;
        }
        if (!FilterStrategy.class.isAssignableFrom(type)) {
            return;
        }
        if (PassthroughStrategy.class.isAssignableFrom(type)) {
            return;
        }
        types.add(type);
    }

    public enum SavingsExemption {
        PASSTHROUGH,
        TOO_SMALL,
        VERBOSE_MODE,
        FAILURE_VERBATIM,
        INTENTIONAL_IDENTITY;

        @JsonCreator
        public static SavingsExemption fromJson(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("savings_exemption must not be blank");
            }
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Catalog(
        @JsonProperty(value = "schema_version", required = true) int schemaVersion,
        @JsonProperty(value = "entries", required = true) List<Entry> entries
    ) {
        void validate() {
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                    "schema_version must be " + SCHEMA_VERSION + ", got " + schemaVersion);
            }
            if (entries == null || entries.isEmpty()) {
                throw new IllegalArgumentException("entries must not be empty");
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Entry entry : entries) {
                entry.validate();
                if (!ids.add(entry.id())) {
                    throw new IllegalArgumentException("Duplicate corpus id: " + entry.id());
                }
            }
        }
    }

    public record Entry(
        @JsonProperty(value = "id", required = true) String id,
        @JsonProperty(value = "command", required = true) String command,
        @JsonProperty(value = "fixture", required = true) String fixture,
        @JsonProperty(value = "exit_code", required = true) int exitCode,
        @JsonProperty("savings_floor") Integer savingsFloor,
        @JsonProperty("meets_contribution_bar") Boolean meetsContributionBar,
        @JsonProperty("savings_exemption") SavingsExemption savingsExemption,
        @JsonProperty(value = "critical_signals", required = true) List<String> criticalSignals
    ) {
        void validate() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException(id + ": command must not be blank");
            }
            if (fixture == null || fixture.isBlank()) {
                throw new IllegalArgumentException(id + ": fixture must not be blank");
            }
            if (criticalSignals == null || criticalSignals.isEmpty()) {
                throw new IllegalArgumentException(id + ": critical_signals must not be empty");
            }
            for (String signal : criticalSignals) {
                if (signal == null || signal.isBlank()) {
                    throw new IllegalArgumentException(id + ": critical_signals must not contain blanks");
                }
            }
            boolean hasFloor = savingsFloor != null;
            boolean hasExemption = savingsExemption != null;
            if (hasFloor == hasExemption) {
                throw new IllegalArgumentException(
                    id + ": exactly one of savings_floor or savings_exemption is required");
            }
            if (hasFloor && (savingsFloor < 0 || savingsFloor > 100)) {
                throw new IllegalArgumentException(id + ": savings_floor must be 0–100");
            }
        }

        public boolean claimsToCompress() {
            return savingsExemption == null;
        }

        public boolean grandfatheredBelowContributionBar() {
            return Boolean.FALSE.equals(meetsContributionBar);
        }

        public boolean requiresContributionBar() {
            return claimsToCompress() && !grandfatheredBelowContributionBar();
        }
    }
}
