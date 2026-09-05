package com.condense.filter.pipeline;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.FilterStrategy;
import com.condense.core.StrategyRegistry;
import com.condense.corpus.CorpusCatalog;
import com.condense.filter.pipeline.config.BuiltinDefinition;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import com.condense.filter.python.PythonFilter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineBackedFilterArchitectureTest {

    @Test
    void everyDomainFilterExceptTheRouterExtendsPipelineBackedFilter() throws Exception {
        List<String> notOnPipeline = new ArrayList<>();
        List<String> overridesApply = new ArrayList<>();
        for (Class<?> type : CorpusCatalog.discoverDomainFilters()) {
            if (type.equals(PythonFilter.class)) {
                assertThat(PipelineBackedFilter.class.isAssignableFrom(type))
                    .as("PythonFilter is a router, not a second engine")
                    .isFalse();
                continue;
            }
            if (!PipelineBackedFilter.class.isAssignableFrom(type)) {
                notOnPipeline.add(type.getName());
                continue;
            }
            if (declaresApply(type)) {
                overridesApply.add(type.getName());
            }
        }
        assertThat(notOnPipeline)
            .as("domain filters must extend PipelineBackedFilter")
            .isEmpty();
        assertThat(overridesApply)
            .as("domain filters must not override apply")
            .isEmpty();
    }

    @Test
    void pipelineBackedFiltersDoNotOverrideBuildPipeline() throws Exception {
        List<String> overrides = new ArrayList<>();
        for (Class<?> type : CorpusCatalog.discoverDomainFilters()) {
            if (!PipelineBackedFilter.class.isAssignableFrom(type)) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if ("buildPipeline".equals(method.getName()) && method.getParameterCount() == 0) {
                    overrides.add(type.getName());
                }
            }
        }
        assertThat(overrides)
            .as("buildPipeline is final on the adapter and must not be redeclared")
            .isEmpty();
    }

    /**
     * Safeguard for {@code 447eeb6}. A filter with no visible prefixes is
     * silently skipped in {@code StrategyRegistry.build()}, which is how
     * {@code npm install} became passthrough in the native image.
     */
    @Test
    void everyDomainFilterExposesPrefixesThroughPrefixesOn() throws Exception {
        List<String> missing = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        for (Class<?> type : CorpusCatalog.discoverDomainFilters()) {
            CommandFilter[] visible = StrategyRegistry.prefixesOn(type);
            if (visible.length == 0) {
                missing.add(type.getName());
                continue;
            }
            List<String> fromRegistry = Arrays.stream(visible)
                .map(CommandFilter::value)
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .toList();
            List<String> fromAnnotations = declaredAnnotationPrefixes(type);
            if (fromRegistry.size() != fromAnnotations.size()
                || !fromAnnotations.containsAll(fromRegistry)
                || !fromRegistry.containsAll(fromAnnotations)) {
                mismatched.add(type.getSimpleName()
                    + " prefixesOn=" + fromRegistry
                    + " annotations=" + fromAnnotations);
            }
        }
        assertThat(missing)
            .as("every FilterStrategy except PassthroughStrategy must have @CommandFilter/@CommandFilters visible via prefixesOn")
            .isEmpty();
        assertThat(mismatched)
            .as("prefixesOn must match the declared @CommandFilter/@CommandFilters values")
            .isEmpty();
    }

    @Test
    void catalogCommandsMatchCommandFilterAnnotations() throws Exception {
        BuiltinDefinitionCatalog catalog = BuiltinDefinitionCatalog.standalone();
        for (Class<?> type : CorpusCatalog.discoverDomainFilters()) {
            if (!PipelineBackedFilter.class.isAssignableFrom(type)) {
                continue;
            }
            PipelineBackedFilter filter = (PipelineBackedFilter) type.getDeclaredConstructor().newInstance();
            String name = filter.definitionName();
            BuiltinDefinition definition = catalog.requiredDefinition(name);
            List<String> annotated = annotatedPrefixes(type);
            List<String> declared = definition.commands().stream()
                .map(c -> c.trim().toLowerCase(Locale.ROOT))
                .toList();
            assertThat(declared)
                .as(type.getSimpleName() + " commands must match @CommandFilter")
                .containsExactlyInAnyOrderElementsOf(annotated);
        }
    }

    @Test
    void pythonFilterOnlyRoutesOrPassthroughs() throws Exception {
        Method apply = PythonFilter.class.getDeclaredMethod(
            "apply",
            String.class,
            com.condense.core.ExecutionResult.class,
            com.condense.core.CondenseConfig.class,
            int.class,
            boolean.class);
        assertThat(Modifier.isPublic(apply.getModifiers())).isTrue();
        assertThat(FilterStrategy.class.isAssignableFrom(PythonFilter.class)).isTrue();
    }

    private static List<String> annotatedPrefixes(Class<?> type) {
        return Arrays.stream(StrategyRegistry.prefixesOn(type))
            .map(CommandFilter::value)
            .map(v -> v.trim().toLowerCase(Locale.ROOT))
            .toList();
    }

    /** Independent of {@link StrategyRegistry#prefixesOn} so a broken unwrap cannot hide. */
    private static List<String> declaredAnnotationPrefixes(Class<?> type) {
        List<String> prefixes = new ArrayList<>();
        CommandFilter one = type.getAnnotation(CommandFilter.class);
        if (one != null) {
            prefixes.add(one.value().trim().toLowerCase(Locale.ROOT));
        }
        CommandFilters many = type.getAnnotation(CommandFilters.class);
        if (many != null) {
            for (CommandFilter filter : many.value()) {
                prefixes.add(filter.value().trim().toLowerCase(Locale.ROOT));
            }
        }
        prefixes.removeIf(String::isBlank);
        return prefixes;
    }

    private static boolean declaresApply(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals("apply") && method.getParameterCount() == 5) {
                return true;
            }
        }
        return false;
    }
}
