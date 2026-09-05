package com.condense.core;

import com.condense.annotation.CommandFilter;
import com.condense.filter.node.NpmInstallFilter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class StrategyRegistryTest {

    @Inject
    StrategyRegistry registry;

    @Inject
    PassthroughStrategy passthrough;

    @Test
    void atLeastOneFilterIsRegistered() {
        assertThat(registry.registeredCommands()).isNotEmpty();
    }

    @Test
    void gitStatusIsRegistered() {
        assertThat(registry.registeredCommands()).contains("git status");
    }

    @Test
    void commandFiltersContainerExposesNpmInstallPrefixes() {
        assertThat(StrategyRegistry.prefixesOn(NpmInstallFilter.class))
            .extracting(CommandFilter::value)
            .contains("npm install", "npm ci", "npm i");
    }

    @Test
    void npmInstallPrefixesAreRegistered() {
        assertThat(registry.registeredCommands()).contains("npm install", "npm ci", "npm i");
    }

    @Test
    void lookupNpmInstallReturnsNpmInstallFilter() {
        FilterStrategy s = registry.lookup(new String[]{"npm", "install"});
        assertThat(s).isNotInstanceOf(PassthroughStrategy.class);
        assertThat(s.getClass().getSimpleName()).startsWith("NpmInstallFilter");
    }

    @Test
    void lookupGitStatusReturnsGitStatusFilter() {
        FilterStrategy s = registry.lookup(new String[]{"git", "status"});
        assertThat(s).isNotInstanceOf(PassthroughStrategy.class);
        // CDI proxies: class name starts with "GitStatusFilter"
        assertThat(s.getClass().getSimpleName()).startsWith("GitStatusFilter");
    }

    @Test
    void lookupWithExtraFlagsStillMatchesPrefix() {
        FilterStrategy s = registry.lookup(new String[]{"git", "status", "--short"});
        assertThat(s.getClass().getSimpleName()).startsWith("GitStatusFilter");
    }

    @Test
    void leftoverMypyPrefixIsRegisteredOnCatalogHost() {
        assertThat(registry.registeredCommands()).contains("mypy", "python -m mypy", "python3 -m mypy");
        FilterStrategy s = registry.lookup(new String[]{"mypy"});
        assertThat(s).isNotInstanceOf(PassthroughStrategy.class);
        assertThat(s.getClass().getName()).contains("CatalogBackedFilter");
    }

    @Test
    void javaBackedPrefixIsNotStolenByCatalogHost() {
        FilterStrategy s = registry.lookup(new String[]{"npm", "install"});
        assertThat(s.getClass().getSimpleName()).startsWith("NpmInstallFilter");
    }

    @Test
    void lookupUnknownCommandReturnsPassthrough() {
        FilterStrategy s = registry.lookup(new String[]{"unknowncmd", "--flag"});
        assertThat(s).isInstanceOf(PassthroughStrategy.class);
    }

    @Test
    void lookupEmptyArgsReturnsPassthrough() {
        assertThat(registry.lookup(new String[]{}))
            .isInstanceOf(PassthroughStrategy.class);
    }

    @Test
    void lookupNullReturnsPassthrough() {
        assertThat(registry.lookup(null))
            .isInstanceOf(PassthroughStrategy.class);
    }

    @Test
    void hasFilterTrueForGitStatus() {
        assertThat(registry.hasFilter(new String[]{"git", "status"})).isTrue();
    }

    @Test
    void hasFilterFalseForUnknown() {
        assertThat(registry.hasFilter(new String[]{"notacommand"})).isFalse();
    }

    @Test
    void registeredCommandsListIsSorted() {
        var cmds = registry.registeredCommands();
        assertThat(cmds).isSortedAccordingTo(String::compareTo);
    }
}
