package com.condense;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlagPositionSemanticsTest {

    @Test
    void childFlagsAfterCommandAreNeverTreatedAsCondenseFlags() {
        CliPreParser.PreParseResult result = CliPreParser.parse("git", "log", "--format=oneline");
        assertThat(result.isSubcommand()).isFalse();
        assertThat(result.condenseArgs()).isEmpty();
        assertThat(result.childArgs()).containsExactly("git", "log", "--format=oneline");
    }

    @Test
    void condenseOptionBeforeCommandIsSeparatedFromChildFlags() {
        CliPreParser.PreParseResult result = CliPreParser.parse("--format", "json", "git", "log", "--format=oneline");
        assertThat(result.isSubcommand()).isFalse();
        assertThat(result.condenseArgs()).containsExactly("--format", "json");
        assertThat(result.childArgs()).containsExactly("git", "log", "--format=oneline");
    }

    @Test
    void condensedOptionWithEqualsIsSeparatedFromChildFlags() {
        CliPreParser.PreParseResult result = CliPreParser.parse("--format=json", "git", "log", "--format=oneline");
        assertThat(result.isSubcommand()).isFalse();
        assertThat(result.condenseArgs()).containsExactly("--format=json");
        assertThat(result.childArgs()).containsExactly("git", "log", "--format=oneline");
    }

    @Test
    void doubleDashExplicitlyDemarcatesChildCommand() {
        CliPreParser.PreParseResult result = CliPreParser.parse(
            "--format", "json", "-v", "--", "git", "log", "--format=oneline"
        );
        assertThat(result.isSubcommand()).isFalse();
        assertThat(result.condenseArgs()).containsExactly("--format", "json", "-v");
        assertThat(result.childArgs()).containsExactly("git", "log", "--format=oneline");
    }

    @Test
    void doubleDashAllowsChildCommandStartingWithDash() {
        CliPreParser.PreParseResult result = CliPreParser.parse("--", "-v");
        assertThat(result.isSubcommand()).isFalse();
        assertThat(result.condenseArgs()).isEmpty();
        assertThat(result.childArgs()).containsExactly("-v");
    }

    @Test
    void doubleDashAllowsSubcommandNameAsChildCommand() {
        CliPreParser.PreParseResult result = CliPreParser.parse("--", "gain", "--daily");
        assertThat(result.isSubcommand()).isFalse();
        assertThat(result.condenseArgs()).isEmpty();
        assertThat(result.childArgs()).containsExactly("gain", "--daily");
    }

    @Test
    void recognizedSubcommandWithoutDelimiterIsSubcommand() {
        CliPreParser.PreParseResult result = CliPreParser.parse("gain", "--daily");
        assertThat(result.isSubcommand()).isTrue();
        assertThat(result.condenseArgs()).containsExactly("gain", "--daily");
        assertThat(result.childArgs()).isEmpty();

        CliPreParser.PreParseResult doctor = CliPreParser.parse("doctor");
        assertThat(doctor.isSubcommand()).isTrue();
    }

    @Test
    void mixedChildFlagsPreserveVerbatimOrder() {
        CliPreParser.PreParseResult result = CliPreParser.parse(
            "-u", "pytest", "-v", "--tb=short", "tests/unit", "-k", "test_foo"
        );
        assertThat(result.isSubcommand()).isFalse();
        assertThat(result.condenseArgs()).containsExactly("-u");
        assertThat(result.childArgs()).containsExactly(
            "pytest", "-v", "--tb=short", "tests/unit", "-k", "test_foo"
        );
    }

    @Test
    void emptyOrHelpArgsHandledGracefully() {
        CliPreParser.PreParseResult empty = CliPreParser.parse();
        assertThat(empty.condenseArgs()).isEmpty();
        assertThat(empty.childArgs()).isEmpty();
        assertThat(empty.isSubcommand()).isFalse();

        CliPreParser.PreParseResult help = CliPreParser.parse("--help");
        assertThat(help.condenseArgs()).containsExactly("--help");
        assertThat(help.childArgs()).isEmpty();
        assertThat(help.isSubcommand()).isFalse();
    }
}
