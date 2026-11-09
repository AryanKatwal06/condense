package com.condense;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the CLI pre-parser routing that lets {@code condense bypass <cmd...>}
 * reach picocli as a real subcommand while preserving every child argument.
 */
class CliPreParserTest {

    @Test
    void routesExpandToCondenseSubcommand() {
        CliPreParser.PreParseResult result =
            CliPreParser.parse("expand", "sample.txt", "10", "20");

        assertThat(result.isSubcommand()).isTrue();
        assertThat(result.condenseArgs()).containsExactly("expand", "sample.txt", "10", "20");
        assertThat(result.childArgs()).isEmpty();
    }

    @Test
    void treatsBypassAsSubcommandAndKeepsChildArguments() {
        CliPreParser.PreParseResult result =
            CliPreParser.parse("bypass", "git", "log", "-n", "5");

        assertThat(result.isSubcommand()).isTrue();
        assertThat(result.condenseArgs())
            .containsExactly("bypass", "git", "log", "-n", "5");
        assertThat(result.childArgs()).isEmpty();
    }

    @Test
    void recognizesBypassAfterCondenseFlags() {
        CliPreParser.PreParseResult result =
            CliPreParser.parse("-v", "bypass", "cargo", "test", "--", "--nocapture");

        assertThat(result.isSubcommand()).isTrue();
    }

    @Test
    void explicitDelimiterKeepsBypassAsAChildArgument() {
        CliPreParser.PreParseResult result = CliPreParser.parse("--", "bypass", "git");

        assertThat(result.isSubcommand()).isFalse();
        assertThat(result.childArgs()).containsExactly("bypass", "git");
    }

    @Test
    void routesOrdinaryCommandToChild() {
        CliPreParser.PreParseResult result = CliPreParser.parse("git", "status");

        assertThat(result.isSubcommand()).isFalse();
        assertThat(result.condenseArgs()).isEmpty();
        assertThat(result.childArgs()).containsExactly("git", "status");
    }

    @Test
    void keepsCondenseFlagsBeforeChild() {
        CliPreParser.PreParseResult result =
            CliPreParser.parse("-vv", "--format", "json", "git", "status");

        assertThat(result.isSubcommand()).isFalse();
        assertThat(result.condenseArgs()).containsExactly("-vv", "--format", "json");
        assertThat(result.childArgs()).containsExactly("git", "status");
    }

    @Test
    void emptyArgumentsProduceEmptyResult() {
        CliPreParser.PreParseResult result = CliPreParser.parse();

        assertThat(result.isSubcommand()).isFalse();
        assertThat(result.condenseArgs()).isEmpty();
        assertThat(result.childArgs()).isEmpty();
    }
}
