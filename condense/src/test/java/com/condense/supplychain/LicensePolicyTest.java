package com.condense.supplychain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces that every runtime dependency shipping in Condense native image
 * adheres to an approved, non-reciprocal open source license.
 */
class LicensePolicyTest {

    private static final String ALLOWLIST = "/supplychain/runtime-dependencies.txt";

    /**
     * Permitted open-source license categories for runtime binary distribution.
     */
    private static final Set<String> PERMITTED_LICENSES = Set.of(
        "Apache-2.0",
        "MIT",
        "BSD-2-Clause",
        "BSD-3-Clause",
        "ISC",
        "Public Domain"
    );

    /**
     * Explicit registry mapping runtime Maven coordinates to their verified licenses.
     */
    private static final Map<String, String> COORDINATE_LICENSES = Map.of(
        "io.quarkus:quarkus-picocli", "Apache-2.0",
        "io.quarkus:quarkus-arc", "Apache-2.0",
        "com.fasterxml.jackson.core:jackson-databind", "Apache-2.0",
        "org.xerial:sqlite-jdbc:3.45.3.0", "Apache-2.0",
        "com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.17.1", "Apache-2.0"
    );

    /**
     * Explicit forbidden license families (copyleft / reciprocal) that must never
     * enter the native binary runtime.
     */
    private static final Set<String> FORBIDDEN_LICENSE_PATTERNS = Set.of(
        "GPL",
        "AGPL",
        "LGPL",
        "SSPL",
        "EUPL"
    );

    @Test
    @DisplayName("Every runtime dependency coordinate has an approved license in the registry")
    void allRuntimeDependenciesHaveApprovedLicenses() throws Exception {
        Set<String> runtimeCoordinates = readRuntimeDependencies();

        for (String coord : runtimeCoordinates) {
            String license = COORDINATE_LICENSES.get(coord);
            assertThat(license)
                .as("Runtime coordinate %s must have a verified license in LicensePolicyTest registry", coord)
                .isNotNull();

            assertThat(PERMITTED_LICENSES)
                .as("License '%s' for coordinate %s must belong to permitted license set", license, coord)
                .contains(license);

            for (String forbidden : FORBIDDEN_LICENSE_PATTERNS) {
                assertThat(license.toUpperCase())
                    .as("License for %s must not be a forbidden reciprocal license (%s)", coord, forbidden)
                    .doesNotContain(forbidden);
            }
        }
    }

    private static Set<String> readRuntimeDependencies() throws Exception {
        InputStream in = LicensePolicyTest.class.getResourceAsStream(ALLOWLIST);
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
