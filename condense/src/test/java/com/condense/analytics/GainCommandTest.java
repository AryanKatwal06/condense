package com.condense.analytics;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class GainCommandTest {

    @Test
    void bareGainDoesNotRequestTopTable() {
        GainCommand command = new GainCommand();
        new CommandLine(command).parseArgs();
        assertThat(command.topRequested()).isFalse();
        assertThat(command.topN()).isEqualTo(10);
    }

    @Test
    void topWithoutCountRequestsTen() {
        GainCommand command = new GainCommand();
        new CommandLine(command).parseArgs("--top");
        assertThat(command.topRequested()).isTrue();
        assertThat(command.topN()).isEqualTo(10);
    }

    @Test
    void topTenIsRequested() {
        GainCommand command = new GainCommand();
        new CommandLine(command).parseArgs("--top", "10");
        assertThat(command.topRequested()).isTrue();
        assertThat(command.topN()).isEqualTo(10);
    }

    @Test
    void topThreeUsesThree() {
        GainCommand command = new GainCommand();
        new CommandLine(command).parseArgs("--top", "3");
        assertThat(command.topRequested()).isTrue();
        assertThat(command.topN()).isEqualTo(3);
    }
}
