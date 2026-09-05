package com.condense.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrefixIndexTest {

    @Test
    void sameClassMayRegisterManyPrefixes() {
        Map<String, FilterStrategy> registry = new LinkedHashMap<>();
        FilterStrategy one = new AlphaFilter();
        PrefixIndex.put(registry, "npm install", one);
        PrefixIndex.put(registry, "npm ci", one);
        PrefixIndex.put(registry, "npm install", one);

        assertThat(registry).hasSize(2);
        assertThat(registry.get("npm install")).isSameAs(one);
        assertThat(registry.get("npm ci")).isSameAs(one);
    }

    @Test
    void twoClassesClaimingTheSamePrefixFailFast() {
        Map<String, FilterStrategy> registry = new LinkedHashMap<>();
        PrefixIndex.put(registry, "git status", new AlphaFilter());

        assertThatThrownBy(() -> PrefixIndex.put(registry, "git status", new BetaFilter()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("git status")
            .hasMessageContaining(AlphaFilter.class.getName())
            .hasMessageContaining(BetaFilter.class.getName());
    }

    @Test
    void twoInstancesOfTheSameClassClaimingOnePrefixFailFast() {
        Map<String, FilterStrategy> registry = new LinkedHashMap<>();
        PrefixIndex.put(registry, "mypy", new AlphaFilter());

        assertThatThrownBy(() -> PrefixIndex.put(registry, "mypy", new AlphaFilter()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mypy")
            .hasMessageContaining(AlphaFilter.class.getName());
    }

    private static final class AlphaFilter implements FilterStrategy {
        @Override
        public FilterResult apply(String command, ExecutionResult result,
                                  CondenseConfig config, int verbose, boolean ultraCompact) {
            return FilterResult.passthrough(result);
        }
    }

    private static final class BetaFilter implements FilterStrategy {
        @Override
        public FilterResult apply(String command, ExecutionResult result,
                                  CondenseConfig config, int verbose, boolean ultraCompact) {
            return FilterResult.passthrough(result);
        }
    }
}
