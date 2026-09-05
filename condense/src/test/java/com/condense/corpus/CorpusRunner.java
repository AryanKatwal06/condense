package com.condense.corpus;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.core.FilterStrategy;
import com.condense.filter.pipeline.CatalogBackedFilter;
import com.condense.filter.pipeline.config.BuiltinDefinition;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class CorpusRunner {

    private static final CondenseConfig CONFIG = CondenseConfig.defaults();
    private static final Map<String, FilterStrategy> INSTANCES = new ConcurrentHashMap<>();

    private CorpusRunner() {}

    public static FilterResult apply(CorpusCatalog.Entry entry) throws Exception {
        return apply(entry, loadFixture(entry.fixture()));
    }

    public static FilterResult apply(CorpusCatalog.Entry entry, String fixtureText) throws Exception {
        String cacheKey = cacheKey(entry.command());
        FilterStrategy filter = INSTANCES.computeIfAbsent(cacheKey, ignored ->
            CorpusCatalog.instantiateForCommand(entry.command()));
        ExecutionResult result = new ExecutionResult(entry.exitCode(), fixtureText, "", 10L);
        FilterResult filtered = filter.apply(entry.command(), result, CONFIG, 0, false);
        return Objects.requireNonNull(filtered, entry.id() + ": FilterStrategy.apply returned null");
    }

    static String cacheKey(String command) {
        try {
            Class<?> type = CorpusCatalog.resolveFilterClass(command);
            if (!CatalogBackedFilter.class.isAssignableFrom(type)) {
                return type.getName();
            }
        } catch (IllegalArgumentException ignored) {
            // leftover catalog definition
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve filter class for " + command, e);
        }
        BuiltinDefinition definition = BuiltinDefinitionCatalog.standalone().findByCommand(command);
        if (definition != null) {
            return "catalog:" + definition.name();
        }
        throw new IllegalArgumentException("No filter cache key for command '" + command + "'");
    }

    public static FilterStrategy filterFor(String command) {
        return INSTANCES.computeIfAbsent(cacheKey(command), ignored ->
            CorpusCatalog.instantiateForCommand(command));
    }

    public static String loadFixture(String fixture) throws Exception {
        String resource = fixture.startsWith("/") ? fixture : "/" + fixture;
        try (InputStream in = CorpusRunner.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
