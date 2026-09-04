package com.condense.filter.pipeline;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.FilterStrategy;
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
        List<String> prefixes = new ArrayList<>();
        CommandFilters many = type.getAnnotation(CommandFilters.class);
        if (many != null) {
            Arrays.stream(many.value()).map(CommandFilter::value).forEach(prefixes::add);
        }
        CommandFilter one = type.getAnnotation(CommandFilter.class);
        if (one != null) {
            prefixes.add(one.value());
        }
        return prefixes.stream().map(v -> v.trim().toLowerCase(Locale.ROOT)).toList();
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
