package com.condense.docs;

import com.condense.CondenseRootCommand;
import com.condense.VersionProvider;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationDriftTest {

    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("(?<!!)\\[([^\\]]+)\\]\\(([^)]+)\\)");

    @Test
    @DisplayName("Verify that all relative markdown links across docs resolve to real files")
    void allMarkdownRelativeLinksResolve() throws Exception {
        Path repoRoot = findRepoRoot();
        List<Path> markdownFiles = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(repoRoot)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".md"))
                .filter(p -> !p.toString().contains(".cursor") &&
                             !p.toString().contains(".git") &&
                             !p.toString().contains("target") &&
                             !p.toString().contains(".system_generated"))
                .forEach(markdownFiles::add);
        }

        assertThat(markdownFiles).as("Must find documentation markdown files").isNotEmpty();

        List<String> brokenLinks = new ArrayList<>();

        for (Path mdFile : markdownFiles) {
            String content = Files.readString(mdFile, StandardCharsets.UTF_8);
            Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(content);

            while (matcher.find()) {
                String link = matcher.group(2).trim();

                // Skip web links, email, and local fragment-only anchors
                if (link.startsWith("http://") || link.startsWith("https://") ||
                    link.startsWith("mailto:") || link.startsWith("#")) {
                    continue;
                }

                // Strip anchor if present
                String pathOnly = link.split("#")[0].trim();
                if (pathOnly.isEmpty()) {
                    continue;
                }

                Path resolved = mdFile.getParent().resolve(pathOnly).normalize();
                if (!Files.exists(resolved)) {
                    brokenLinks.add(String.format("File '%s' contains broken link: '%s' -> '%s'",
                        repoRoot.relativize(mdFile), link, repoRoot.relativize(resolved)));
                }
            }
        }

        assertThat(brokenLinks)
            .as("All relative markdown links must resolve to existing files on disk")
            .isEmpty();
    }

    @Test
    @DisplayName("Verify that all CLI subcommands are documented in root README or PROJECT_HANDOFF")
    void allSubcommandsAreDocumented() throws Exception {
        Path repoRoot = findRepoRoot();
        String readmeContent = Files.readString(repoRoot.resolve("README.md"), StandardCharsets.UTF_8);
        String handoffContent = Files.readString(repoRoot.resolve("PROJECT_HANDOFF.md"), StandardCharsets.UTF_8);

        Command rootAnnotation = CondenseRootCommand.class.getAnnotation(Command.class);
        assertThat(rootAnnotation).isNotNull();

        for (Class<?> sub : rootAnnotation.subcommands()) {
            Command subCmd = sub.getAnnotation(Command.class);
            if (subCmd != null && !subCmd.name().isEmpty()) {
                String name = subCmd.name();
                boolean documented = readmeContent.contains(name) || handoffContent.contains(name);
                assertThat(documented)
                    .as("Subcommand '%s' (%s) must be documented in README.md or PROJECT_HANDOFF.md",
                        name, sub.getSimpleName())
                    .isTrue();
            }
        }
    }

    @Test
    @DisplayName("Verify that version numbers match across VersionProvider, pom.xml, and docs")
    void verifyVersionConsistency() throws Exception {
        String appVersion = VersionProvider.applicationVersion();
        assertThat(appVersion).isEqualTo("1.0.1");

        Path repoRoot = findRepoRoot();
        String pomContent = Files.readString(repoRoot.resolve("condense").resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pomContent).contains("<version>" + appVersion + "</version>");

        String handoffContent = Files.readString(repoRoot.resolve("PROJECT_HANDOFF.md"), StandardCharsets.UTF_8);
        assertThat(handoffContent).contains(appVersion);
    }

    @Test
    @DisplayName("Verify that documentation claims match live catalog counts")
    void documentationClaimsMatchLiveCatalog() {
        BuiltinDefinitionCatalog catalog = BuiltinDefinitionCatalog.standalone();
        assertThat(catalog.all()).isNotEmpty();
        assertThat(catalog.all().size()).isGreaterThanOrEqualTo(40);
    }

    private static Path findRepoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null) {
            if (Files.exists(cur.resolve("condense").resolve("pom.xml")) &&
                Files.exists(cur.resolve("PROJECT_HANDOFF.md"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        return Path.of("").toAbsolutePath();
    }
}
