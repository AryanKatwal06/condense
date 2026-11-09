package com.condense;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bypass subcommand must receive the child argv exactly as typed, including
 * option-looking tokens and a literal "--" that belongs to the child command.
 */
class BypassCommandParsingTest {

    @Test
    void keepsChildOptionsAndDelimiterVerbatim() {
        BypassCommand bypass = new BypassCommand();
        CommandLine line = new CommandLine(bypass);
        line.setStopAtPositional(true);
        line.setUnmatchedArgumentsAllowed(true);

        line.parseArgs("cargo", "test", "--", "--nocapture");

        assertThat(bypass.command)
            .containsExactly("cargo", "test", "--", "--nocapture");
    }

    @Test
    void emptyCommandReturnsUsageExitCode() {
        BypassCommand bypass = new BypassCommand();
        CommandLine line = new CommandLine(bypass);

        assertThat(line.execute()).isEqualTo(2);
    }

    @Test
    void helpReturnsZero() {
        BypassCommand bypass = new BypassCommand();
        CommandLine line = new CommandLine(bypass);

        assertThat(line.execute("--help")).isZero();
    }
}
