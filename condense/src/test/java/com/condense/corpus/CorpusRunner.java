package com.condense.corpus;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.core.FilterStrategy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class CorpusRunner {

    private static final CondenseConfig CONFIG = CondenseConfig.defaults();
    private static final Map<Class<?>, FilterStrategy> INSTANCES = new ConcurrentHashMap<>();

    private CorpusRunner() {}

    static FilterResult apply(CorpusCatalog.Entry entry) throws Exception {
        return apply(entry, loadFixture(entry.fixture()));
    }

    static FilterResult apply(CorpusCatalog.Entry entry, String fixtureText) throws Exception {
        Class<?> type = CorpusCatalog.resolveFilterClass(entry.command());
        FilterStrategy filter = INSTANCES.computeIfAbsent(type, CorpusCatalog::instantiate);
        ExecutionResult result = new ExecutionResult(entry.exitCode(), fixtureText, "", 10L);
        FilterResult filtered = filter.apply(entry.command(), result, CONFIG, 0, false);
        return Objects.requireNonNull(filtered, entry.id() + ": FilterStrategy.apply returned null");
    }

    static String loadFixture(String fixture) throws Exception {
        String resource = fixture.startsWith("/") ? fixture : "/" + fixture;
        try (InputStream in = CorpusRunner.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
