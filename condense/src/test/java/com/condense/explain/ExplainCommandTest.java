package com.condense.explain;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainCommandTest {

    @Test
    void missingCommandExitsOne() {
        ExplainCommand command = new ExplainCommand(new ExplainService());
        int exit = new CommandLine(command).execute();
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void inputAndStdinTogetherExitsOne() {
        ExplainCommand command = new ExplainCommand(new ExplainService());
        int exit = new CommandLine(command).execute("--input", "fixture.txt", "--stdin", "pytest");
        assertThat(exit).isEqualTo(1);
    }
}
