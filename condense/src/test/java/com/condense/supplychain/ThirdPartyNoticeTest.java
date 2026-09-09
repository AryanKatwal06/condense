package com.condense.supplychain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that THIRD_PARTY_LICENSES.md accounts for and attributes every
 * runtime coordinate that ships in Condense distributions.
 */
class ThirdPartyNoticeTest {

    private static final String ALLOWLIST = "/supplychain/runtime-dependencies.txt";

    @Test
    @DisplayName("THIRD_PARTY_LICENSES.md exists and contains attribution for all runtime dependencies")
    void thirdPartyNoticeCoversAllRuntimeDependencies() throws Exception {
        Path noticeFile = findRootFile("THIRD_PARTY_LICENSES.md");
        assertThat(Files.isRegularFile(noticeFile))
            .as("THIRD_PARTY_LICENSES.md must exist at repository root")
            .isTrue();

        String content = Files.readString(noticeFile, StandardCharsets.UTF_8);
        assertThat(content)
            .as("THIRD_PARTY_LICENSES.md must not be empty")
            .isNotEmpty();

        Set<String> runtimeCoordinates = readRuntimeDependencies();
        for (String coord : runtimeCoordinates) {
            String artifact = coord.contains(":") ? coord.split(":")[1] : coord;
            assertThat(content)
                .as("THIRD_PARTY_LICENSES.md must attribute runtime artifact %s (from %s)", artifact, coord)
                .contains(artifact);
        }

        // Also ensure key sections exist
        assertThat(content).contains("Approved License Allowlist Policy");
        assertThat(content).contains("Quarkus");
        assertThat(content).contains("Jackson");
        assertThat(content).contains("SQLite");
    }

    private static Path findRootFile(String name) {
        Path cwd = Path.of(System.getProperty("user.dir", "."));
        Path direct = cwd.resolve(name);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path parent = cwd.resolve("..").resolve(name).normalize();
        if (Files.isRegularFile(parent)) {
            return parent;
        }
        throw new AssertionError("Could not find " + name + " from " + cwd.toAbsolutePath());
    }

    private static Set<String> readRuntimeDependencies() throws Exception {
        InputStream in = ThirdPartyNoticeTest.class.getResourceAsStream(ALLOWLIST);
        assertThat(in).as(ALLOWLIST + " must exist on test classpath").isNotNull();

        Set<String> lines = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                lines.add(trimmed);
            }
        }
        assertThat(lines).isNotEmpty();
        return lines;
    }
}
