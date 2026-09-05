package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.trust.Provenance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogBackedFilterTest {

    @Test
    void mypyHostUsesDefinitionNameAsFilterName() {
        CatalogBackedFilter filter = new CatalogBackedFilter("mypy");
        assertThat(filter.definitionName()).isEqualTo("mypy");
        assertThat(filter.filterName()).isEqualTo("mypy");
    }

    @Test
    void mypyApplyKeepsFilePathsAndStamps() {
        CatalogBackedFilter filter = new CatalogBackedFilter("mypy");
        String raw = """
            src/billing/invoice.py:12: error: Incompatible types in assignment  [assignment]
            src/billing/invoice.py:18: error: Argument 1 to "save" has incompatible type  [arg-type]
            src/auth/session.py:8: error: Name "AnonymousUser" is not defined  [name-defined]
            Found 3 errors in 2 files (checked 9 source files)
            """;
        ExecutionResult execution = new ExecutionResult(1, raw, "", 10L);
        FilterResult result = filter.apply("mypy", execution, CondenseConfig.defaults(), 0, false);
        assertThat(result.wasFiltered()).isTrue();
        assertThat(result.output()).startsWith(Provenance.STAMP);
        assertThat(result.output()).contains("src/billing/invoice.py", "src/auth/session.py");
        assertThat(result.output()).doesNotContain("[assignment]");
    }

    @Test
    void defaultSelectInputPrefersStdout() {
        CatalogBackedFilter filter = new CatalogBackedFilter("mypy");
        ExecutionResult execution = new ExecutionResult(1, "src/a.py:1: error: x  [assignment]\n", "ignored\n", 10L);
        FilterResult result = filter.apply("mypy", execution, CondenseConfig.defaults(), 0, false);
        assertThat(result.output()).contains("src/a.py");
        assertThat(result.output()).doesNotContain("ignored");
    }
}
