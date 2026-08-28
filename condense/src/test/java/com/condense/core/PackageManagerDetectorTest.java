package com.condense.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PackageManagerDetectorTest {

    @Test
    void detect_nullPath_returnsEmpty() {
        Optional<PackageManagerDetector.Detection> result = PackageManagerDetector.detect(null);
        assertThat(result).isEmpty();
    }

    @Test
    void detect_standaloneInstallPaths_returnsEmpty() {
        Path unixManual = Path.of("/usr/local/bin/condense");
        Path unixLocal = Path.of("/home/user/.local/bin/condense");
        Path windowsProgramFiles = Path.of("C:\\Program Files\\Condense\\condense.exe");
        Path windowsCustom = Path.of("D:\\tools\\condense\\condense.exe");

        assertThat(PackageManagerDetector.detect(unixManual)).isEmpty();
        assertThat(PackageManagerDetector.detect(unixLocal)).isEmpty();
        assertThat(PackageManagerDetector.detect(windowsProgramFiles)).isEmpty();
        assertThat(PackageManagerDetector.detect(windowsCustom)).isEmpty();
    }

    @Test
    void detect_scoopPaths_returnsScoopDetection() {
        Path userApp = Path.of("C:\\Users\\user\\scoop\\apps\\condense\\1.0.1\\condense.exe");
        Path userAppCurrent = Path.of("C:\\Users\\user\\scoop\\apps\\condense\\current\\condense.exe");
        Path userShim = Path.of("C:\\Users\\user\\scoop\\shims\\condense.exe");
        Path globalApp = Path.of("C:\\ProgramData\\scoop\\apps\\condense\\1.0.1\\condense.exe");
        Path globalShim = Path.of("C:\\ProgramData\\scoop\\shims\\condense.exe");

        assertDetection(userApp, "Scoop", "scoop uninstall condense");
        assertDetection(userAppCurrent, "Scoop", "scoop uninstall condense");
        assertDetection(userShim, "Scoop", "scoop uninstall condense");
        assertDetection(globalApp, "Scoop", "scoop uninstall condense");
        assertDetection(globalShim, "Scoop", "scoop uninstall condense");
    }

    @Test
    void detect_homebrewPaths_returnsHomebrewDetection() {
        Path appleSiliconCellar = Path.of("/opt/homebrew/Cellar/condense/1.0.1/bin/condense");
        Path appleSiliconBin = Path.of("/opt/homebrew/bin/condense");
        Path intelCellar = Path.of("/usr/local/Cellar/condense/1.0.1/bin/condense");
        Path linuxbrewCellar = Path.of("/home/linuxbrew/.linuxbrew/Cellar/condense/1.0.1/bin/condense");
        Path linuxbrewBin = Path.of("/home/linuxbrew/.linuxbrew/bin/condense");

        assertDetection(appleSiliconCellar, "Homebrew", "brew uninstall condense");
        assertDetection(appleSiliconBin, "Homebrew", "brew uninstall condense");
        assertDetection(intelCellar, "Homebrew", "brew uninstall condense");
        assertDetection(linuxbrewCellar, "Homebrew", "brew uninstall condense");
        assertDetection(linuxbrewBin, "Homebrew", "brew uninstall condense");
    }

    @Test
    void detect_wingetPaths_returnsWinGetDetection() {
        Path userPortable = Path.of("C:\\Users\\user\\AppData\\Local\\Microsoft\\WinGet\\Packages\\AryanKatwal.Condense_Microsoft.Winget.Source_8wekyb3d8bbwe\\condense.exe");
        Path machinePortable = Path.of("C:\\Program Files\\WinGet\\Packages\\AryanKatwal.Condense_Microsoft.Winget.Source_8wekyb3d8bbwe\\condense.exe");

        assertDetection(userPortable, "WinGet", "winget uninstall condense");
        assertDetection(machinePortable, "WinGet", "winget uninstall condense");
    }

    @Test
    void detect_unrelatedAppInPackageManagers_returnsEmpty() {
        Path otherScoop = Path.of("C:\\Users\\user\\scoop\\apps\\ripgrep\\14.1.0\\rg.exe");
        Path otherBrewCellar = Path.of("/opt/homebrew/Cellar/ripgrep/14.1.0/bin/rg");
        Path otherBrewBin = Path.of("/opt/homebrew/bin/rg");

        assertThat(PackageManagerDetector.detect(otherScoop)).isEmpty();
        assertThat(PackageManagerDetector.detect(otherBrewCellar)).isEmpty();
        assertThat(PackageManagerDetector.detect(otherBrewBin)).isEmpty();
    }

    private void assertDetection(Path path, String expectedManager, String expectedUninstallCommand) {
        Optional<PackageManagerDetector.Detection> detection = PackageManagerDetector.detect(path);
        assertThat(detection).isPresent();
        assertThat(detection.get().managerName()).isEqualTo(expectedManager);
        assertThat(detection.get().uninstallCommand()).isEqualTo(expectedUninstallCommand);
    }
}
