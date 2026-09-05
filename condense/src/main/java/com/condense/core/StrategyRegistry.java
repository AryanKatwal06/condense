package com.condense.core;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@ApplicationScoped
public class StrategyRegistry {

    private static final Logger log = Logger.getLogger(StrategyRegistry.class);

    private final Map<String, FilterStrategy> registry = new LinkedHashMap<>();

    @Inject
    @Any
    Instance<FilterStrategy> strategies;

    @Inject
    PassthroughStrategy passthrough;

    @PostConstruct
    void build() {
        for (var handle : strategies.handles()) {
            Class<?> cls = handle.getBean().getBeanClass();

            // The passthrough is the explicit fallback — never register it
            if (PassthroughStrategy.class.isAssignableFrom(cls)) continue;

            CommandFilter[] annotations = prefixesOn(cls);
            if (annotations.length == 0) continue;

            FilterStrategy instance = handle.get();
            for (CommandFilter annotation : annotations) {
                String key = annotation.value().trim().toLowerCase();
                if (key.isBlank()) {
                    log.warnf("Empty @CommandFilter value on %s — skipping",
                        cls.getSimpleName());
                    continue;
                }
                PrefixIndex.put(registry, key, instance);
                log.debugf("Registered '%s' → %s", key, cls.getSimpleName());
            }
        }
        log.infof("StrategyRegistry: %d filter(s) registered", registry.size());
    }

    /**
     * Returns the best matching {@link FilterStrategy} for the given arguments.
     *
     * <p>Tries prefixes from longest to shortest. Falls back to
     * {@link PassthroughStrategy} if no prefix matches.
     *
     * @param args the command tokens as passed to condense; may be null or empty
     * @return the matching strategy; never null
     */
    public FilterStrategy lookup(String[] args) {
        if (args == null || args.length == 0) return passthrough;

        for (int len = args.length; len >= 1; len--) {
            String prefix = Arrays.stream(args, 0, len)
                .collect(Collectors.joining(" "))
                .toLowerCase()
                .trim();

            FilterStrategy strategy = registry.get(prefix);
            if (strategy != null) {
                log.debugf("Matched '%s' → %s",
                    prefix, strategy.getClass().getSimpleName());
                return strategy;
            }
        }

        log.debugf("No filter for '%s' — passthrough", String.join(" ", args));
        return passthrough;
    }

    public boolean hasFilter(String[] args) {
        return lookup(args) != passthrough;
    }

    public List<String> registeredCommands() {
        return registry.keySet().stream().sorted().toList();
    }

    /**
     * {@link CommandFilter} is {@link java.lang.annotation.Repeatable}; native-image
     * can still miss the unwrap, so also read the {@link CommandFilters} container.
     */
    static CommandFilter[] prefixesOn(Class<?> cls) {
        CommandFilter[] annotations = cls.getAnnotationsByType(CommandFilter.class);
        if (annotations.length > 0) {
            return annotations;
        }
        CommandFilters container = cls.getAnnotation(CommandFilters.class);
        return container == null ? annotations : container.value();
    }
}
