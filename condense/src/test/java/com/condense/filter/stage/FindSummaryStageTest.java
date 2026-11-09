package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FindSummaryStageTest {

    @Test
    void keepsDiscoveredPathsAndExtensionCounts() {
        String input = "src/main/App.java\nsrc/test/AppTest.java\nREADME\n"
            + IntStream.rangeClosed(1, 38)
                .mapToObj(i -> "src/generated/Generated" + i + ".java")
                .collect(Collectors.joining("\n", "", ""));

        String output = FindSummaryStage.INSTANCE.process(input, FilterContext.empty()).output();

        assertThat(output)
            .contains("src/main/: App.java")
            .contains("src/test/: AppTest.java")
            .contains("41 result(s)")
            .contains(".java: 40");
    }

    @Test
    void capsPathsWithExplicitOmissionMarker() {
        String input = IntStream.rangeClosed(1, 125)
            .mapToObj(i -> "src/files/File" + i + ".java")
            .collect(Collectors.joining("\n"));

        String output = FindSummaryStage.INSTANCE.process(input, FilterContext.empty()).output();

        assertThat(output)
            .contains("src/files/: File1.java")
            .contains("File100.java")
            .contains("... (25 paths omitted) ...")
            .contains("125 result(s)")
            .contains(".java: 125")
            .doesNotContain("src/files/File101.java");
    }
}
