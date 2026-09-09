package com.condense.supplychain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates cross-channel packaging manifest consistency across .deb, RPM,
 * Homebrew, Scoop, and WinGet. Ensures version, repository URL, and license
 * alignment while strictly enforcing architecture truthfulness (§404).
 */
class PackagingManifestConsistencyTest {

    private static final String EXPECTED_VERSION = "1.0.1";
    private static final String EXPECTED_LICENSE = "Apache-2.0";
    private static final String EXPECTED_REPO_URL = "https://github.com/AryanKatwal06/condense";

    @Test
    @DisplayName("Debian package packaging metadata matches canonical project properties")
    void debianPackageMetadataMatchesCanonical() throws Exception {
        Path controlFile = findPackagingPath("deb/control");
        Path buildDeb = findPackagingPath("deb/build-deb.sh");

        assertThat(Files.isRegularFile(controlFile)).isTrue();
        assertThat(Files.isRegularFile(buildDeb)).isTrue();

        String control = Files.readString(controlFile, StandardCharsets.UTF_8);
        String build = Files.readString(buildDeb, StandardCharsets.UTF_8);

        assertThat(control).contains("Package: condense");
        assertThat(control).contains(EXPECTED_REPO_URL);
        assertThat(build).contains("dpkg-deb --build");
    }

    @Test
    @DisplayName("RPM specification matches canonical project properties")
    void rpmSpecMatchesCanonical() throws Exception {
        Path specFile = findPackagingPath("rpm/condense.spec");
        assertThat(Files.isRegularFile(specFile)).isTrue();

        String spec = Files.readString(specFile, StandardCharsets.UTF_8);
        assertThat(spec).contains("Name:           condense");
        assertThat(spec).contains("Version:        " + EXPECTED_VERSION);
        assertThat(spec).contains("License:        " + EXPECTED_LICENSE);
        assertThat(spec).contains("URL:            " + EXPECTED_REPO_URL);
    }

    @Test
    @DisplayName("Homebrew formula matches canonical properties and disallows unbuilt macOS Intel")
    void homebrewFormulaMatchesCanonical() throws Exception {
        Path brewFile = findPackagingPath("homebrew/condense.rb");
        assertThat(Files.isRegularFile(brewFile)).isTrue();

        String brew = Files.readString(brewFile, StandardCharsets.UTF_8);
        assertThat(brew).contains("class Condense < Formula");
        assertThat(brew).contains("version \"" + EXPECTED_VERSION + "\"");
        assertThat(brew).contains("homepage \"" + EXPECTED_REPO_URL + "\"");

        // Supported architectures
        assertThat(brew).contains("condense-macos-aarch64");
        assertThat(brew).contains("condense-linux-x64");
        assertThat(brew).contains("condense-linux-aarch64");

        // Intel macOS must explicitly disallow pre-built downloads (§404)
        assertThat(brew).contains("Intel macOS pre-built binaries are not available");
    }

    @Test
    @DisplayName("Scoop manifest matches canonical properties and contains zero unbuilt arm64 references")
    void scoopManifestMatchesCanonicalAndExcludesUnbuiltArm64() throws Exception {
        Path scoopFile = findPackagingPath("scoop/condense.json");
        assertThat(Files.isRegularFile(scoopFile)).isTrue();

        String scoop = Files.readString(scoopFile, StandardCharsets.UTF_8);
        assertThat(scoop).contains("\"version\": \"" + EXPECTED_VERSION + "\"");
        assertThat(scoop).contains("\"license\": \"" + EXPECTED_LICENSE + "\"");
        assertThat(scoop).contains("\"homepage\": \"" + EXPECTED_REPO_URL + "\"");

        // Must support 64bit Windows
        assertThat(scoop).contains("condense-windows-x64.exe");

        // Must NOT declare unbuilt windows-aarch64 (§404 compliance)
        assertThat(scoop)
            .as("Scoop manifest must not advertise unbuilt windows-aarch64 binary")
            .doesNotContain("windows-aarch64");
    }

    @Test
    @DisplayName("WinGet manifest matches canonical properties and contains zero unbuilt arm64 references")
    void wingetManifestMatchesCanonicalAndExcludesUnbuiltArm64() throws Exception {
        Path wingetFile = findPackagingPath("winget/com.condense.condense.yaml");
        assertThat(Files.isRegularFile(wingetFile)).isTrue();

        String winget = Files.readString(wingetFile, StandardCharsets.UTF_8);
        assertThat(winget).contains("PackageIdentifier: com.condense.condense");
        assertThat(winget).contains("PackageVersion: " + EXPECTED_VERSION);
        assertThat(winget).contains("License: " + EXPECTED_LICENSE);

        // Must support x64 Windows
        assertThat(winget).contains("Architecture: x64");
        assertThat(winget).contains("condense-windows-x64.exe");

        // Must NOT declare unbuilt arm64 (§404 compliance)
        assertThat(winget)
            .as("WinGet manifest must not advertise unbuilt arm64 installer")
            .doesNotContain("Architecture: arm64")
            .doesNotContain("windows-aarch64");
    }

    private static Path findPackagingPath(String subpath) {
        Path cwd = Path.of(System.getProperty("user.dir", "."));
        Path direct = cwd.resolve("packaging").resolve(subpath);
        if (Files.exists(direct)) {
            return direct;
        }
        Path nested = cwd.resolve("condense").resolve("packaging").resolve(subpath);
        if (Files.exists(nested)) {
            return nested;
        }
        Path parent = cwd.resolve("..").resolve("packaging").resolve(subpath).normalize();
        if (Files.exists(parent)) {
            return parent;
        }
        Path parentNested = cwd.resolve("..").resolve("condense").resolve("packaging").resolve(subpath).normalize();
        if (Files.exists(parentNested)) {
            return parentNested;
        }
        throw new AssertionError("Could not find packaging/" + subpath + " from " + cwd.toAbsolutePath());
    }
}
