package com.condense.cli;

import com.condense.CondenseRootCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CliMetadataConsistencyTest {

    @Test
    @DisplayName("Man page and shell completion scripts stay in sync with Picocli command tree")
    void metadataIsInSyncWithCommandTree() throws Exception {
        CommandLine cmd = new CommandLine(new CondenseRootCommand());
        CommandSpec spec = cmd.getCommandSpec();

        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        Path packagingDir = repoRoot.resolve("condense").resolve("packaging");
        if (!Files.exists(packagingDir)) {
            packagingDir = repoRoot.resolve("packaging");
        }
        if (!Files.exists(packagingDir)) {
            packagingDir = Path.of("packaging").toAbsolutePath();
        }

        Path manPage = packagingDir.resolve("man").resolve("condense.1");
        Path bashCompletion = packagingDir.resolve("completions").resolve("condense.bash");
        Path zshCompletion = packagingDir.resolve("completions").resolve("condense.zsh");
        Path fishCompletion = packagingDir.resolve("completions").resolve("condense.fish");

        assertThat(manPage).as("Man page must exist at " + manPage).exists();
        assertThat(bashCompletion).as("Bash completion must exist at " + bashCompletion).exists();
        assertThat(zshCompletion).as("Zsh completion must exist at " + zshCompletion).exists();
        assertThat(fishCompletion).as("Fish completion must exist at " + fishCompletion).exists();

        String manContent = Files.readString(manPage).replace("\\-", "-");
        String bashContent = Files.readString(bashCompletion);
        String zshContent = Files.readString(zshCompletion);
        String fishContent = Files.readString(fishCompletion);

        // Verify top-level subcommands are present in man page and completions
        List<String> keySubcommands = List.of(
            "gain", "doctor", "discover", "propose", "explain", "read", "init", "config", "mcp"
        );
        for (String sub : keySubcommands) {
            assertThat(spec.subcommands()).containsKey(sub);
            assertThat(manContent).contains("condense " + sub);
            assertThat(bashContent).contains(sub);
            assertThat(zshContent).contains(sub);
            assertThat(fishContent).contains(sub);
        }

        // Verify essential Phase 8 flags and options are documented
        List<String> expectedFlags = List.of(
            "--plain", "--ascii", "--format"
        );
        for (String flag : expectedFlags) {
            assertThat(manContent).contains(flag);
            assertThat(bashContent).contains(flag);
            assertThat(zshContent).contains(flag);
            String fishFlag = flag.startsWith("--") ? "-l " + flag.substring(2) : flag;
            assertThat(fishContent).contains(fishFlag);
        }

        // Verify delimiter and CSV format documentation
        assertThat(manContent).contains("--");
        assertThat(manContent).contains("csv");
        assertThat(zshContent).contains("csv");
        assertThat(fishContent).contains("csv");
    }
}
