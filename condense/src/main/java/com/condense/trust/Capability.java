package com.condense.trust;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Capability class a project override must be granted before its stages may run.
 */
public enum Capability {
    REDUCE,
    RESHAPE,
    REWRITE;

    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Capability parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("capability must not be blank");
        }
        return Capability.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    public static Set<Capability> parseAll(Iterable<String> raw) {
        Set<Capability> caps = new LinkedHashSet<>();
        if (raw == null) {
            return caps;
        }
        for (String item : raw) {
            if (item != null && !item.isBlank()) {
                caps.add(parse(item));
            }
        }
        return caps;
    }

    public static Set<Capability> parseCsv(String csv) {
        Set<Capability> caps = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return caps;
        }
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                caps.add(parse(part));
            }
        }
        return caps;
    }

    public static boolean grantsCover(Set<Capability> granted, Set<Capability> required) {
        return granted != null && required != null && granted.containsAll(required);
    }
}
