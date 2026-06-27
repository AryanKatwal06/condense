package com.zapproxy.core;

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
