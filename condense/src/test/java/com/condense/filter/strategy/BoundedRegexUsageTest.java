package com.condense.filter.strategy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture gate: every {@code .matcher(} in the filter package must go through
 * {@link BoundedRegex} (or live in {@link TimeoutCharSequence}, which has none).
 */
class BoundedRegexUsageTest {

    @Test
    void filterSourcesDoNotCallMatcherDirectly() throws IOException {
        Path root = resolveFilterSourceRoot();
        assertThat(root).isDirectory();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().equals("BoundedRegex.java"))
                .filter(p -> !p.getFileName().toString().equals("TimeoutCharSequence.java"))
                .forEach(p -> collectViolations(root, p, violations));
        }

        assertThat(violations)
            .as("raw Pattern.matcher calls must go through BoundedRegex")
            .isEmpty();
    }

    private static void collectViolations(Path root, Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains(".matcher(")
                && !line.contains("BoundedRegex.matcher")
                && !line.trim().startsWith("//")
                && !line.trim().startsWith("*")) {
                violations.add(root.relativize(file) + ":" + (i + 1) + ": " + line.trim());
            }
        }
    }

    private static Path resolveFilterSourceRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        Path nested = cwd.resolve("src/main/java/com/condense/filter");
        if (Files.isDirectory(nested)) {
            return nested;
        }
        Path fromRoot = cwd.resolve("condense/src/main/java/com/condense/filter");
        if (Files.isDirectory(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("Cannot find filter sources from " + cwd);
    }
}
