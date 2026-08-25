package com.condense.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PackageManagerDetectorTest {

    @Test
    void detect_identifiesScoop() {
        Path p1 = Path.of("C:\\Users\\alice\\scoop\\apps\\condense\\1.0.1\\condense.exe");
        Path p2 = Path.of("C:\\Users\\alice\\scoop\\shims\\condense.exe");

        Optional<PackageManagerDetector.Detection> d1 = PackageManagerDetector.detect(p1);
        assertThat(d1).isPresent();
        assertThat(d1.get().managerName()).isEqualTo("Scoop");
        assertThat(d1.get().uninstallCommand()).isEqualTo("scoop uninstall condense");

        Optional<PackageManagerDetector.Detection> d2 = PackageManagerDetector.detect(p2);
        assertThat(d2).isPresent();
        assertThat(d2.get().managerName()).isEqualTo("Scoop");
    }

    @Test
    void detect_identifiesHomebrewMacAndLinux() {
        Path p1 = Path.of("/opt/homebrew/Cellar/condense/1.0.1/bin/condense");
        Path p2 = Path.of("/usr/local/Cellar/condense/1.0.1/bin/condense");
        Path p3 = Path.of("/home/linuxbrew/.linuxbrew/Cellar/condense/1.0.1/bin/condense");
        Path p4 = Path.of("/opt/homebrew/bin/condense");

        assertThat(PackageManagerDetector.detect(p1)).hasValueSatisfying(d -> {
            assertThat(d.managerName()).isEqualTo("Homebrew");
            assertThat(d.uninstallCommand()).isEqualTo("brew uninstall condense");
        });
        assertThat(PackageManagerDetector.detect(p2)).hasValueSatisfying(d -> {
            assertThat(d.managerName()).isEqualTo("Homebrew");
            assertThat(d.uninstallCommand()).isEqualTo("brew uninstall condense");
        });
        assertThat(PackageManagerDetector.detect(p3)).hasValueSatisfying(d -> {
            assertThat(d.managerName()).isEqualTo("Homebrew");
        });
        assertThat(PackageManagerDetector.detect(p4)).hasValueSatisfying(d -> {
            assertThat(d.managerName()).isEqualTo("Homebrew");
        });
    }

    @Test
    void detect_identifiesWinGet() {
        Path p = Path.of("C:\\Users\\alice\\AppData\\Local\\Microsoft\\WinGet\\Packages\\AryanKatwal06.condense_x64\\condense.exe");

        Optional<PackageManagerDetector.Detection> d = PackageManagerDetector.detect(p);
        assertThat(d).isPresent();
        assertThat(d.get().managerName()).isEqualTo("WinGet");
        assertThat(d.get().uninstallCommand()).isEqualTo("winget uninstall condense");
    }

    @Test
    void detect_returnsEmptyForStandaloneInstall() {
        Path pLinux = Path.of("/home/alice/.local/bin/condense");
        Path pMac = Path.of("/usr/local/bin/condense");
        Path pWin = Path.of("C:\\Users\\alice\\.local\\bin\\condense.exe");
        Path pCustom = Path.of("/opt/custom/bin/condense");

        assertThat(PackageManagerDetector.detect(pLinux)).isEmpty();
        assertThat(PackageManagerDetector.detect(pMac)).isEmpty();
        assertThat(PackageManagerDetector.detect(pWin)).isEmpty();
        assertThat(PackageManagerDetector.detect(pCustom)).isEmpty();
        assertThat(PackageManagerDetector.detect(null)).isEmpty();
    }
}
