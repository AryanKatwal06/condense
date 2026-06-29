package com.condense.core;

import com.condense.annotation.CommandFilter;
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

/**
 * Resolves the correct {@link FilterStrategy} for a given shell command.
 *
 * <p>At CDI startup ({@link PostConstruct}), all {@code FilterStrategy} beans
 * annotated with {@link CommandFilter} are discovered and registered by their
 * declared command prefix. At runtime, {@link #lookup(String[])} performs
 * longest-prefix matching.
 *
 * <h2>Longest-prefix matching</h2>
 * For args {@code ["git", "status", "--short"]}, the registry tries:
 * <ol>
 *   <li>{@code "git status --short"} — no match</li>
 *   <li>{@code "git status"} — match → {@code GitStatusFilter}</li>
 * </ol>
 * Falls back to {@link PassthroughStrategy} if nothing matches.
 *
 * <h2>Extensibility</h2>
 * Adding a new filter in Phase 3 requires only:
 * <ol>
 *   <li>Create a new {@code @ApplicationScoped} class implementing
 *       {@link FilterStrategy}</li>
 *   <li>Annotate it with {@code @CommandFilter("your command")}</li>
 * </ol>
 * No changes to this class are needed.
 */
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

            CommandFilter[] annotations = cls.getAnnotationsByType(CommandFilter.class);
            if (annotations.length == 0) continue;

            FilterStrategy instance = handle.get();
            for (CommandFilter annotation : annotations) {
                String key = annotation.value().trim().toLowerCase();
                if (key.isBlank()) {
                    log.warnf("Empty @CommandFilter value on %s — skipping",
                        cls.getSimpleName());
                    continue;
                }
                registry.put(key, instance);
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

    /**
     * Returns {@code true} if a non-passthrough filter is registered for
     * this command.
     */
    public boolean hasFilter(String[] args) {
        return lookup(args) != passthrough;
    }

    /**
     * Returns all registered command prefixes in sorted order.
     * Useful for diagnostics and the {@code condense init --show} command.
     */
    public List<String> registeredCommands() {
        return registry.keySet().stream().sorted().toList();
    }
}
