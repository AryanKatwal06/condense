package com.condense.filter.pipeline;

import com.condense.core.FilterStrategy;
import com.condense.corpus.CorpusCatalog;
import com.condense.filter.python.PythonFilter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

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

    private static boolean declaresApply(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals("apply") && method.getParameterCount() == 5) {
                return true;
            }
        }
        return false;
    }
}
