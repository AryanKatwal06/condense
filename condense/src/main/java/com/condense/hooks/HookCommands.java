package com.condense.hooks;

import com.condense.core.StrategyRegistry;
import com.condense.filter.pipeline.config.BuiltinDefinition;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * First-token intercept list for hook scripts. Filled at install time from
 * {@link StrategyRegistry#registeredCommands()} when a registry is present,
 * otherwise from the builtin catalog so leftovers such as {@code mypy} still
 * appear without CDI.
 */
public final class HookCommands {

    public static final String PLACEHOLDER = "{{CONDENSE_COMMANDS}}";

    private HookCommands() {}

    public static String spaceSeparated(StrategyRegistry registry) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String prefix : prefixes(registry)) {
            String token = firstToken(prefix);
            if (token != null) {
                tokens.add(token);
            }
        }
        return String.join(" ", tokens);
    }

    static List<String> prefixes(StrategyRegistry registry) {
        if (registry != null) {
            return registry.registeredCommands();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (BuiltinDefinition definition : BuiltinDefinitionCatalog.standalone().all()) {
            for (String command : definition.commands()) {
                if (command != null && !command.isBlank()) {
                    out.add(command.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(out);
    }

    static String firstToken(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        String token = prefix.trim().split("\\s+")[0];
        return token.isBlank() ? null : token;
    }
}
