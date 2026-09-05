package com.condense.core;

import java.util.Map;
import java.util.Objects;

/**
 * Registers command-prefix → filter bindings and refuses a silent last-write-wins
 * collision between two different filter instances.
 *
 * <p>The same instance may claim many prefixes ({@code npm install} / {@code npm ci}).
 * Two instances claiming one prefix fail fast, even when they share a class —
 * catalog hosts are many instances of one type.
 */
public final class PrefixIndex {

    private PrefixIndex() {}

    public static void put(Map<String, FilterStrategy> registry, String key, FilterStrategy instance) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(instance, "instance");
        FilterStrategy existing = registry.get(key);
        if (existing != null && existing != instance) {
            throw new IllegalStateException(
                "Duplicate command prefix '" + key + "' claimed by "
                    + existing.getClass().getName() + " and " + instance.getClass().getName());
        }
        registry.put(key, instance);
    }
}
