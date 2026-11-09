package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class GrepSummaryStageTest {

    @Test
    void keepsPathLineNumberAndMatchedCode() {
        String input = IntStream.rangeClosed(1, 41)
            .mapToObj(line -> "src/auth/Login.java:" + line + ":    authenticate(user);")
            .collect(Collectors.joining("\n"));

        String output = GrepSummaryStage.INSTANCE.process(input, FilterContext.empty()).output();

        assertThat(output)
            .contains("41 match(es) in 1 file(s)")
            .contains("src/auth/Login.java:1:    authenticate(user);")
            .contains("src/auth/Login.java:12:    authenticate(user);");
    }

    @Test
    void parsesWindowsDriveLetterPathsAsOneFilename() {
        String input = "C:\\Users\\dev\\App.java:42:match\n"
            + "C:\\Users\\dev\\App.java:43:another match\n"
            + "D:/work/Other.java:7:third match\n"
            + IntStream.rangeClosed(8, 47)
                .mapToObj(line -> "D:/work/Other.java:" + line + ":match " + line)
                .collect(Collectors.joining("\n", "\n", ""));

        String output = GrepSummaryStage.INSTANCE.process(input, FilterContext.empty()).output();

        assertThat(output)
            .contains("43 match(es) in 2 file(s)")
            .contains("C:\\Users\\dev\\App.java:42:match")
            .contains("D:/work/Other.java:7:third match");
    }

    @Test
    void capsMatchesPerFileAndTotalWithExplicitOmissionCount() {
        String input = IntStream.rangeClosed(1, 100)
            .mapToObj(line -> "src/a.java:" + line + ":match " + line)
            .collect(Collectors.joining("\n"));

        String output = GrepSummaryStage.INSTANCE.process(input, FilterContext.empty()).output();

        assertThat(output)
            .contains("100 match(es) in 1 file(s)")
            .contains("src/a.java:1:match 1")
            .contains("src/a.java:12:match 12")
            .contains("... (88 matches omitted) ...")
            .doesNotContain("src/a.java:13:");
    }
}
