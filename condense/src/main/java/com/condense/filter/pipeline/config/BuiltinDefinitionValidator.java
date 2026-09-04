package com.condense.filter.pipeline.config;

import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Build-time validator for classpath builtin filter definitions.
 * Invoked from Maven {@code process-classes}. Unreferenced from runtime entry points.
 */
public final class BuiltinDefinitionValidator {

    private BuiltinDefinitionValidator() {}

    public static void main(String[] args) {
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            System.err.println("Builtin filter definition validation failed:");
            for (String error : errors) {
                System.err.println("  " + error);
            }
            System.exit(1);
        }
        System.out.println("Builtin filter definitions are valid");
    }

    public static List<String> validate() {
        List<String> errors = new ArrayList<>();
        BuiltinDefinitionCatalog catalog = BuiltinDefinitionCatalog.load(errors);
        if (!errors.isEmpty()) {
            return errors;
        }
        errors.addAll(checkIndexMatchesDirectory(catalog));
        errors.addAll(InlineDefinitionTestRunner.runAll(catalog.all()));
        return errors;
    }

    static List<String> checkIndexMatchesDirectory(BuiltinDefinitionCatalog catalog) {
        List<String> errors = new ArrayList<>();
        URL indexUrl = BuiltinDefinitionCatalog.class.getResource(BuiltinDefinitionCatalog.INDEX_RESOURCE);
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
            errors.add("Cannot compare index.toml with filters directory: " + e.getMessage());
        }
        return errors;
    }
}
