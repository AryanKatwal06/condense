package com.condense.core;

import com.condense.core.Mappers;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FailureContractCatalogTest {

    @Test
    void everyCatalogIdMapsToATestMethod() throws Exception {
        Catalog catalog;
        try (InputStream in = FailureContractCatalogTest.class.getResourceAsStream(
                "/reliability/failure-contract.json")) {
            assertThat(in).as("failure-contract.json must be on the test classpath").isNotNull();
            catalog = Mappers.JSON.readValue(in, Catalog.class);
        }
        assertThat(catalog.schemaVersion()).isEqualTo(1);
        assertThat(catalog.entries()).isNotEmpty();

        Set<String> ids = new HashSet<>();
        for (Entry entry : catalog.entries()) {
            assertThat(entry.id())
                .as("catalog ids must be unique")
                .isNotBlank();
            assertThat(ids.add(entry.id()))
                .as("duplicate catalog id %s", entry.id())
                .isTrue();
            assertThat(entry.test())
                .as("%s must map to Class#method", entry.id())
                .contains("#");
            int split = entry.test().lastIndexOf('#');
            String className = entry.test().substring(0, split);
            String methodName = entry.test().substring(split + 1);
            Class<?> type = Class.forName(className);
            Method method = type.getDeclaredMethod(methodName);
            assertThat(method.getAnnotation(Test.class))
                .as("%s must name a @Test method", entry.id())
                .isNotNull();
            if (entry.nativeIt()) {
                assertThat(className).contains("Native");
                assertThat(className).endsWith("IT");
            }
        }
    }

    public record Catalog(int schemaVersion, List<Entry> entries) {}

    public record Entry(
        String id,
        String mode,
        String test,
        String exitPolicy,
        String outputRetention,
        String diagnostic,
        boolean nativeIt
    ) {}
}
