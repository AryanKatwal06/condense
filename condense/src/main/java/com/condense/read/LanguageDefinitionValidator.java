package com.condense.read;

import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Build-time validator for classpath language definitions.
 * Invoked from Maven {@code process-classes}.
 */
public final class LanguageDefinitionValidator {

    private LanguageDefinitionValidator() {}

    public static void main(String[] args) {
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            System.err.println("Builtin language definition validation failed:");
            for (String error : errors) {
                System.err.println("  " + error);
            }
            System.exit(1);
        }
        System.out.println("Builtin language definitions are valid");
    }

    public static List<String> validate() {
        List<String> errors = new ArrayList<>();
        LanguageDefinitionCatalog catalog = LanguageDefinitionCatalog.load(errors);
        if (!errors.isEmpty()) {
            return errors;
        }
        errors.addAll(checkIndexMatchesDirectory(catalog));
        errors.addAll(runInlineTests(catalog));
        return errors;
    }

    static List<String> runInlineTests(LanguageDefinitionCatalog catalog) {
        List<String> errors = new ArrayList<>();
        for (CompiledLanguage language : catalog.all()) {
            for (LanguageDefinition.InlineTest test : language.definition().tests()) {
                try {
                    String output = renderInline(language, test);
                    for (String needle : test.expectedContains()) {
                        if (needle != null && !output.contains(needle)) {
                            errors.add(language.name() + " test '" + test.id()
                                + "' missing expected text: " + needle);
                        }
                    }
                    for (String needle : test.expectedAbsent()) {
                        if (needle != null && !needle.isEmpty() && output.contains(needle)) {
                            errors.add(language.name() + " test '" + test.id()
                                + "' still contains forbidden text: " + needle);
                        }
                    }
                } catch (Exception e) {
                    errors.add(language.name() + " test '" + test.id() + "' failed: " + e.getMessage());
                }
            }
        }
        return errors;
    }

    static String renderInline(CompiledLanguage language, LanguageDefinition.InlineTest test) {
        ReadLevel level = ReadLevel.parse(test.level() == null ? "comments" : test.level());
        SourceScanner.Classification classified = SourceScanner.classify(test.input(), language);
        if (level == ReadLevel.VERBATIM) {
            return test.input() == null ? "" : test.input();
        }
        List<ReadRenderer.KeptLine> stripped = ReadRenderer.commentStrippedLines(classified);
        if (level == ReadLevel.OUTLINE) {
            List<ReadRenderer.KeptLine> outlined = ReadRenderer.outlineLines(stripped, language);
            if (!outlined.isEmpty() || stripped.isEmpty()) {
                return ReadRenderer.joinPlain(outlined);
            }
            return ReadRenderer.joinPlain(stripped);
        }
        return ReadRenderer.joinPlain(stripped);
    }

    static List<String> checkIndexMatchesDirectory(LanguageDefinitionCatalog catalog) {
        List<String> errors = new ArrayList<>();
        URL indexUrl = LanguageDefinitionCatalog.class.getResource(LanguageDefinitionCatalog.INDEX_RESOURCE);
        if (indexUrl == null || !"file".equalsIgnoreCase(indexUrl.getProtocol())) {
            return errors;
        }
        try {
            Path indexPath = Path.of(indexUrl.toURI());
            Path dir = indexPath.getParent();
            if (dir == null || !Files.isDirectory(dir)) {
                return errors;
            }
            Set<String> onDisk = new LinkedHashSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.toml")) {
                for (Path file : stream) {
                    String fileName = file.getFileName().toString();
                    if ("index.toml".equals(fileName)) {
                        continue;
                    }
                    onDisk.add(fileName.substring(0, fileName.length() - ".toml".length()));
                }
            }
            Set<String> indexed = new LinkedHashSet<>(catalog.names());
            for (String name : indexed) {
                if (!onDisk.contains(name)) {
                    errors.add("index.toml lists '" + name + "' but " + name + ".toml is missing on disk");
                }
            }
            for (String name : onDisk) {
                if (!indexed.contains(name)) {
                    errors.add(name + ".toml exists on disk but is not listed in index.toml");
                }
            }
        } catch (Exception e) {
            errors.add("Cannot compare index.toml with languages directory: " + e.getMessage());
        }
        return errors;
    }
}
