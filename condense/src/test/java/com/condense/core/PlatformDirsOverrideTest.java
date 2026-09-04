package com.condense.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermetic tests for {@code CONDENSE_CONFIG_DIR} / {@code CONDENSE_DATA_DIR}.
 * Calls package-private resolvers with synthetic values so the developer's
 * real home directory is never read or created.
 */
class PlatformDirsOverrideTest {

    @TempDir
    Path tempDir;

    @Test
    void configOverrideTakesPrecedenceOnEveryOs() {
        Path override = tempDir.resolve("cfg");
        for (String os : new String[] {"Linux", "Mac OS X", "Windows 11"}) {
            Path resolved = PlatformDirs.resolveConfigBase(
                os, override.toString(), "/xdg-config", "C:\\AppData", "/home/dev"
            );
            assertThat(resolved).isEqualTo(override);
        }
    }

    @Test
    void dataOverrideTakesPrecedenceOnEveryOs() {
        Path override = tempDir.resolve("data");
        for (String os : new String[] {"Linux", "Mac OS X", "Windows 11"}) {
            Path resolved = PlatformDirs.resolveDataBase(
                os, override.toString(), "/xdg-data", "C:\\AppData", "/home/dev"
            );
            assertThat(resolved).isEqualTo(override);
        }
    }

    @Test
    void blankConfigOverrideFallsThroughToOsLogic() {
        Path resolved = PlatformDirs.resolveConfigBase(
            "Mac OS X", "  ", "/xdg-config", "C:\\AppData", "/Users/dev"
        );
        assertThat(resolved).isEqualTo(Path.of("/Users/dev", "Library", "Application Support", "condense"));
    }

    @Test
    void nullConfigOverrideFallsThroughToOsLogic() {
        Path resolved = PlatformDirs.resolveConfigBase(
            "Linux", null, "/xdg-config", null, "/home/dev"
        );
        assertThat(resolved).isEqualTo(Path.of("/xdg-config", "condense"));
    }

    @Test
    void blankDataOverrideFallsThroughToOsLogic() {
        Path resolved = PlatformDirs.resolveDataBase(
            "Linux", "", "/xdg-data", null, "/home/dev"
        );
        assertThat(resolved).isEqualTo(Path.of("/xdg-data", "condense"));
    }

    @Test
    void configAndDataOverridesAreIndependent() {
        Path config = tempDir.resolve("only-config");
        Path data = tempDir.resolve("only-data");

        Path resolvedConfig = PlatformDirs.resolveConfigBase(
            "Windows 11", config.toString(), null, "C:\\AppData", "C:\\Users\\dev"
        );
        Path resolvedDataWithoutOverride = PlatformDirs.resolveDataBase(
            "Windows 11", null, null, "C:\\AppData", "C:\\Users\\dev"
        );
        Path resolvedDataWithOverride = PlatformDirs.resolveDataBase(
            "Windows 11", data.toString(), null, "C:\\AppData", "C:\\Users\\dev"
        );

        assertThat(resolvedConfig).isEqualTo(config);
        assertThat(resolvedDataWithoutOverride).isEqualTo(Path.of("C:\\AppData", "condense"));
        assertThat(resolvedDataWithOverride).isEqualTo(data);
    }

    @Test
    void whitespaceOnlyOverrideIsTreatedAsUnset() {
        Path resolved = PlatformDirs.resolveConfigBase(
            "Linux", "\t\n", null, null, "/home/dev"
        );
        assertThat(resolved).isEqualTo(Path.of("/home/dev", ".config", "condense"));
    }
}
