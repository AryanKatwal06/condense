package com.zapproxy.annotation;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI qualifier that maps a {@link com.zapproxy.core.FilterStrategy} implementation
 * to one or more command prefixes.
 *
 * <p>Example usage:
 * <pre>{@code
 * @CommandFilter("git status")
 * @ApplicationScoped
 * public class GitStatusFilter implements FilterStrategy { ... }
 * }</pre>
 *
 * <p>The value must be the command prefix as the user types it — lowercase,
 * space-separated tokens (e.g., "git status", "cargo test", "pytest").
 * The {@link com.zapproxy.core.StrategyRegistry} performs longest-prefix matching,
 * so "git status" takes priority over "git" when the command is "git status --short".
 *
 * <p>Multiple commands that share the same filter logic should each be annotated
 * separately using {@link CommandFilters}.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface CommandFilter {
    String value();
}
