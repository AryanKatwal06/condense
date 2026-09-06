package com.condense.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time binding from a {@link com.condense.filter.pipeline.FilterStage}
 * implementation to its TOML strategy aliases. The annotation processor emits
 * {@code GeneratedStageRegistry}; runtime never reflects on this annotation.
 *
 * <p>{@code aliases[0]} is the canonical snake_case name used by explain.
 * Exactly one of {@code singleton} or {@code factory} must be set.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface DeclarativeStage {
    String[] aliases();

    String capability();

    String singleton() default "";

    String factory() default "";
}
