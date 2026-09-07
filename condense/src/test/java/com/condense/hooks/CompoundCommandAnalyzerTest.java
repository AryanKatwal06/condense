package com.condense.hooks;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompoundCommandAnalyzerTest {

    private static final Set<String> CONDENSE_COMMANDS = Set.of(
        "git", "npm", "cargo", "pytest", "terraform", "dotnet", "docker", "mvn"
    );

    @Test
    void bareCommandMatchesAndDenies() {
        var res = CompoundCommandAnalyzer.analyze("git status", CONDENSE_COMMANDS);
        assertThat(res.shouldDeny()).isTrue();
        assertThat(res.decision()).isEqualTo(CompoundCommandAnalyzer.Decision.DENY);
        assertThat(res.matchedCommands()).containsExactly("git");
        assertThat(res.formattedReason("git status")).contains("condense git status");
    }

    @Test
    void pathAndExtensionAreStripped() {
        var unix = CompoundCommandAnalyzer.analyze("/usr/local/bin/git diff", CONDENSE_COMMANDS);
        assertThat(unix.shouldDeny()).isTrue();
        assertThat(unix.matchedCommands()).containsExactly("git");

        var win = CompoundCommandAnalyzer.analyze("C:\\Tools\\git.exe log -n 5", CONDENSE_COMMANDS);
        assertThat(win.shouldDeny()).isTrue();
        assertThat(win.matchedCommands()).containsExactly("git");

        var cmd = CompoundCommandAnalyzer.analyze("npm.cmd run test", CONDENSE_COMMANDS);
        assertThat(cmd.shouldDeny()).isTrue();
        assertThat(cmd.matchedCommands()).containsExactly("npm");
    }

    @Test
    void quotesAndEscapesAroundCommandAreStripped() {
        var quoted = CompoundCommandAnalyzer.analyze("\"git\" status", CONDENSE_COMMANDS);
        assertThat(quoted.shouldDeny()).isTrue();
        assertThat(quoted.matchedCommands()).containsExactly("git");

        var singleQuoted = CompoundCommandAnalyzer.analyze("'cargo' test", CONDENSE_COMMANDS);
        assertThat(singleQuoted.shouldDeny()).isTrue();
        assertThat(singleQuoted.matchedCommands()).containsExactly("cargo");

        var escaped = CompoundCommandAnalyzer.analyze("\\git status", CONDENSE_COMMANDS);
        assertThat(escaped.shouldDeny()).isTrue();
        assertThat(escaped.matchedCommands()).containsExactly("git");
    }

    @Test
    void leadingEnvAssignmentsAreIgnored() {
        var res = CompoundCommandAnalyzer.analyze("GIT_PAGER=cat git status", CONDENSE_COMMANDS);
        assertThat(res.shouldDeny()).isTrue();
        assertThat(res.matchedCommands()).containsExactly("git");

        var multiple = CompoundCommandAnalyzer.analyze("A=1 B=\"val with space\" _DEBUG=true npm test", CONDENSE_COMMANDS);
        assertThat(multiple.shouldDeny()).isTrue();
        assertThat(multiple.matchedCommands()).containsExactly("npm");
    }

    @Test
    void leadingWrappersAreSkipped() {
        var sudo = CompoundCommandAnalyzer.analyze("sudo git status", CONDENSE_COMMANDS);
        assertThat(sudo.shouldDeny()).isTrue();
        assertThat(sudo.matchedCommands()).containsExactly("git");

        var sudoOptions = CompoundCommandAnalyzer.analyze("sudo -u deploy -E git pull", CONDENSE_COMMANDS);
        assertThat(sudoOptions.shouldDeny()).isTrue();
        assertThat(sudoOptions.matchedCommands()).containsExactly("git");

        var env = CompoundCommandAnalyzer.analyze("env FOO=bar cargo build", CONDENSE_COMMANDS);
        assertThat(env.shouldDeny()).isTrue();
        assertThat(env.matchedCommands()).containsExactly("cargo");
    }

    @Test
    void chainedCommandsWithOperatorsAreCaught() {
        var andRes = CompoundCommandAnalyzer.analyze("echo starting && git status", CONDENSE_COMMANDS);
        assertThat(andRes.shouldDeny()).isTrue();
        assertThat(andRes.matchedCommands()).containsExactly("git");

        var semiRes = CompoundCommandAnalyzer.analyze("echo starting; git status; echo done", CONDENSE_COMMANDS);
        assertThat(semiRes.shouldDeny()).isTrue();
        assertThat(semiRes.matchedCommands()).containsExactly("git");

        var orRes = CompoundCommandAnalyzer.analyze("pytest || echo failed", CONDENSE_COMMANDS);
        assertThat(orRes.shouldDeny()).isTrue();
        assertThat(orRes.matchedCommands()).containsExactly("pytest");

        var multiIntercept = CompoundCommandAnalyzer.analyze("git status && npm test", CONDENSE_COMMANDS);
        assertThat(multiIntercept.shouldDeny()).isTrue();
        assertThat(multiIntercept.matchedCommands()).containsExactly("git", "npm");
    }

    @Test
    void multilineAndCarriageReturnCommandsAreCaught() {
        var newline = CompoundCommandAnalyzer.analyze("echo start\ngit status\necho end", CONDENSE_COMMANDS);
        assertThat(newline.shouldDeny()).isTrue();
        assertThat(newline.matchedCommands()).containsExactly("git");

        var crlf = CompoundCommandAnalyzer.analyze("echo start\r\ngit status\r\necho end", CONDENSE_COMMANDS);
        assertThat(crlf.shouldDeny()).isTrue();
        assertThat(crlf.matchedCommands()).containsExactly("git");
    }

    @Test
    void pipelinesAreCaught() {
        var pipe = CompoundCommandAnalyzer.analyze("git status | grep modified", CONDENSE_COMMANDS);
        assertThat(pipe.shouldDeny()).isTrue();
        assertThat(pipe.matchedCommands()).containsExactly("git");
    }

    @Test
    void substitutionsAreAmbiguousAndDenied() {
        var subshell = CompoundCommandAnalyzer.analyze("echo $(git status)", CONDENSE_COMMANDS);
        assertThat(subshell.shouldDeny()).isTrue();
        assertThat(subshell.isAmbiguous() || subshell.decision() == CompoundCommandAnalyzer.Decision.DENY).isTrue();

        var backtick = CompoundCommandAnalyzer.analyze("echo `git status`", CONDENSE_COMMANDS);
        assertThat(backtick.shouldDeny()).isTrue();

        var arbitrarySub = CompoundCommandAnalyzer.analyze("echo $(date)", CONDENSE_COMMANDS);
        assertThat(arbitrarySub.shouldDeny()).isTrue();
        assertThat(arbitrarySub.isAmbiguous()).isTrue();
    }

    @Test
    void hereDocRedirectionIsAmbiguous() {
        var heredoc = CompoundCommandAnalyzer.analyze("cat <<EOF\ngit status\nEOF", CONDENSE_COMMANDS);
        assertThat(heredoc.shouldDeny()).isTrue();
        assertThat(heredoc.isAmbiguous()).isTrue();
        assertThat(heredoc.reason()).contains("Here-document");
    }

    @Test
    void dynamicExecutionCommandsAreAmbiguous() {
        var evalCmd = CompoundCommandAnalyzer.analyze("eval 'git status'", CONDENSE_COMMANDS);
        assertThat(evalCmd.shouldDeny()).isTrue();
        assertThat(evalCmd.isAmbiguous()).isTrue();
    }

    @Test
    void unclosedQuotesAndSyntaxErrorsAreAmbiguous() {
        var unclosed = CompoundCommandAnalyzer.analyze("\"git status", CONDENSE_COMMANDS);
        assertThat(unclosed.shouldDeny()).isTrue();
        assertThat(unclosed.isAmbiguous()).isTrue();
        assertThat(unclosed.reason()).contains("Unclosed");

        var danglingEscape = CompoundCommandAnalyzer.analyze("git status \\", CONDENSE_COMMANDS);
        assertThat(danglingEscape.shouldDeny()).isTrue();
        assertThat(danglingEscape.isAmbiguous()).isTrue();
    }

    @Test
    void unicodeWhitespaceIsNormalized() {
        var nbsp = CompoundCommandAnalyzer.analyze("\u00A0git\u00A0status\u00A0", CONDENSE_COMMANDS);
        assertThat(nbsp.shouldDeny()).isTrue();
        assertThat(nbsp.matchedCommands()).containsExactly("git");

        var narrowNbsp = CompoundCommandAnalyzer.analyze("\u202Fgit status", CONDENSE_COMMANDS);
        assertThat(narrowNbsp.shouldDeny()).isTrue();
        assertThat(narrowNbsp.matchedCommands()).containsExactly("git");
    }

    @Test
    void safeCommandsWithoutCondenseAreAllowed() {
        var echo = CompoundCommandAnalyzer.analyze("echo 'Hello World'", CONDENSE_COMMANDS);
        assertThat(echo.shouldAllow()).isTrue();
        assertThat(echo.shouldDeny()).isFalse();

        var chained = CompoundCommandAnalyzer.analyze("echo starting && ls -la && date", CONDENSE_COMMANDS);
        assertThat(chained.shouldAllow()).isTrue();
        assertThat(chained.shouldDeny()).isFalse();

        var dirOps = CompoundCommandAnalyzer.analyze("mkdir -p build && cd build && touch index.html", CONDENSE_COMMANDS);
        assertThat(dirOps.shouldAllow()).isTrue();
        assertThat(dirOps.shouldDeny()).isFalse();
    }

    @Test
    void redirectsDoNotHideCommand() {
        var redirect = CompoundCommandAnalyzer.analyze("git status > out.txt 2>&1", CONDENSE_COMMANDS);
        assertThat(redirect.shouldDeny()).isTrue();
        assertThat(redirect.matchedCommands()).containsExactly("git");

        var inputRedirect = CompoundCommandAnalyzer.analyze("< input.txt git commit -F -", CONDENSE_COMMANDS);
        assertThat(inputRedirect.shouldDeny()).isTrue();
        assertThat(inputRedirect.matchedCommands()).containsExactly("git");
    }

    @Test
    void emptyOrBlankCommandIsAllowed() {
        assertThat(CompoundCommandAnalyzer.analyze("", CONDENSE_COMMANDS).shouldAllow()).isTrue();
        assertThat(CompoundCommandAnalyzer.analyze("   ", CONDENSE_COMMANDS).shouldAllow()).isTrue();
        assertThat(CompoundCommandAnalyzer.analyze(null, CONDENSE_COMMANDS).shouldAllow()).isTrue();
    }
}
