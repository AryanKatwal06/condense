package com.condense.supplychain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mechanically verifies that SECURITY.md is accurate, complete, and contains
 * all mandatory sections: active supported versions, private reporting channel,
 * explicit response SLAs, threat model references, and Cosign verification.
 */
class SecurityPolicyVerificationTest {

    @Test
    @DisplayName("SECURITY.md contains valid supported version table, SLAs, and verification guidance")
    void securityPolicyContainsAllMandatoryClauses() throws Exception {
        Path securityMd = findRootFile("SECURITY.md");
        assertThat(Files.isRegularFile(securityMd))
            .as("SECURITY.md must exist at repository root")
            .isTrue();

        String content = Files.readString(securityMd, StandardCharsets.UTF_8);

        // 1. Supported versions table
        assertThat(content)
            .as("SECURITY.md must declare supported versions table")
            .contains("| Version | Supported |")
            .contains("1.0.x");

        // 2. Private reporting URL
        assertThat(content)
            .as("SECURITY.md must direct reporters to GitHub Security Advisories")
            .contains("https://github.com/AryanKatwal06/condense/security/advisories/new");

        // 3. Explicit SLA targets
        assertThat(content)
            .as("SECURITY.md must commit to response and remediation SLAs")
            .contains("24 hours")
            .contains("7 days")
            .contains("14 days")
            .contains("30 days");

        // 4. Threat model and backward compatibility references
        assertThat(content)
            .as("SECURITY.md must reference docs/threat-model.md")
            .contains("docs/threat-model.md");
        assertThat(content)
            .as("SECURITY.md must reference docs/backward-compatibility-sla.md")
            .contains("docs/backward-compatibility-sla.md");

        // 5. Cross-platform Cosign verification guidance
        assertThat(content)
            .as("SECURITY.md must include bash Cosign verification instructions")
            .contains("cosign verify-blob");
        assertThat(content)
            .as("SECURITY.md must include PowerShell verification instructions")
            .contains("Get-FileHash");
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
}
