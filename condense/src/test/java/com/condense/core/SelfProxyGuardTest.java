package com.condense.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelfProxyGuardTest {

    @Test
    void literalCondenseIsRefused() {
        assertThatThrownBy(() -> CommandExecutor.checkSelfProxy(List.of("condense")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("refusing to execute");
    }

    @Test
    void condenseExeIsRefused() {
        assertThatThrownBy(() -> CommandExecutor.checkSelfProxy(List.of("condense.exe")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("refusing to execute");
    }

    @Test
    void pathEndingCondenseRunnerIsRefused() {
        String nested = Path.of("opt", "bin", "condense-runner").toString();
        assertThatThrownBy(() -> CommandExecutor.checkSelfProxy(List.of(nested)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("refusing to execute");
    }

    @Test
    void unrelatedGitIsNotBlocked() {
        assertThatCode(() -> CommandExecutor.checkSelfProxy(List.of("git", "status")))
            .doesNotThrowAnyException();
    }
}
