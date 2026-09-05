package com.condense.propose;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProposeIsolationTest {

    private static final List<String> PROXY_PATH = List.of(
        "src/main/java/com/condense/core/ProxyService.java",
        "src/main/java/com/condense/core/StreamingProxy.java",
        "src/main/java/com/condense/core/StrategyRegistry.java",
        "src/main/java/com/condense/core/CommandExecutor.java",
        "src/main/java/com/condense/filter/pipeline/config/FilterOverrideLoader.java",
        "src/main/java/com/condense/filter/pipeline/CatalogBackedFilter.java"
    );

    @Test
    void proxyPathSourcesDoNotImportPropose() throws Exception {
        for (String relative : PROXY_PATH) {
            Path file = Path.of(relative);
            assertThat(file).as(relative).exists();
            String source = Files.readString(file);
            assertThat(source)
                .as(relative + " must not reference com.condense.propose")
                .doesNotContain("com.condense.propose");
        }
    }
}
