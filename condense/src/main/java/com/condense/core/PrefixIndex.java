package com.condense.core;

import java.util.Map;
import java.util.Objects;

/**
 * Registers command-prefix → filter bindings and refuses a silent last-write-wins
 * collision between two different filter classes.
 *
 * <p>The same class may claim many prefixes ({@code npm install} / {@code npm ci}).
 * Two classes claiming one prefix fail fast.
 */
public final class PrefixIndex {

    private PrefixIndex() {}

    public static void put(Map<String, FilterStrategy> registry, String key, FilterStrategy instance) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(instance, "instance");
        FilterStrategy existing = registry.get(key);
        if (existing != null) {
            Class<?> existingClass = existing.getClass();
            Class<?> incomingClass = instance.getClass();
            if (!existingClass.equals(incomingClass)) {
                throw new IllegalStateException(
                    "Duplicate @CommandFilter prefix '" + key + "' claimed by "
                        + existingClass.getName() + " and " + incomingClass.getName());
            }
        }
        registry.put(key, instance);
    }
}
