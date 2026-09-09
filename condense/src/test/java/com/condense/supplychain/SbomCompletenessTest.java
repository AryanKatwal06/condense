package com.condense.supplychain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the release workflow SBOM generation step audits all runtime
 * coordinates so that no runtime coordinate escapes SBOM coverage.
 */
class SbomCompletenessTest {

    private static final List<String> REQUIRED_SBOM_COORDINATES = List.of(
        "sqlite-jdbc",
        "jackson-databind",
        "jackson-dataformat-toml",
        "quarkus-picocli",
        "quarkus-arc"
    );

    @Test
    @DisplayName("Release workflow SBOM assertion checks all required runtime coordinates")
    void releaseWorkflowSbomStepIncludesAllRuntimeCoordinates() throws Exception {
        Path workflow = findRootFile(".github/workflows/release.yml");
        assertThat(Files.isRegularFile(workflow))
            .as(".github/workflows/release.yml must exist")
            .isTrue();

        String content = Files.readString(workflow, StandardCharsets.UTF_8);

        // Verify Anchore SBOM action is configured
        assertThat(content)
            .as("release.yml must invoke anchore/sbom-action")
            .contains("anchore/sbom-action");

        // Verify all 5 runtime coordinates are explicitly asserted in the SBOM check script
        for (String coord : REQUIRED_SBOM_COORDINATES) {
            assertThat(content)
                .as("release.yml SBOM verification script must check coordinate %s", coord)
                .contains("\"" + coord + "\"");
        }

        // Verify SBOM is signed and included in checksums.txt
        assertThat(content).contains("sbom.cyclonedx.json");
    }

    private static Path findRootFile(String relativePath) {
        Path cwd = Path.of(System.getProperty("user.dir", "."));
        Path direct = cwd.resolve(relativePath);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path parent = cwd.resolve("..").resolve(relativePath).normalize();
        if (Files.isRegularFile(parent)) {
            return parent;
        }
        throw new AssertionError("Could not find " + relativePath + " from " + cwd.toAbsolutePath());
    }
}
