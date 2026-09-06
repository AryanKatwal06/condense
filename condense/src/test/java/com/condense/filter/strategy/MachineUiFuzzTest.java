package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MachineUiFuzzTest {

    @Test
    void truncationAtStructuralBoundariesNeverThrowsAndKeepsPrefixWhenPossible() {
        String full = read("/fixtures/terraform/plan-json.ndjson");
        List<Integer> cuts = new ArrayList<>();
        cuts.add(0);
        cuts.add(1);
        cuts.add(full.indexOf('{'));
        cuts.add(full.indexOf('\n') + 1);
        int second = full.indexOf('\n', full.indexOf('\n') + 1);
        if (second > 0) {
            cuts.add(second + 1);
        }
        int planned = full.indexOf("planned_change");
        if (planned > 0) {
            cuts.add(planned);
            cuts.add(Math.min(full.length(), planned + 20));
        }
        cuts.add(full.length() / 2);
        cuts.add(full.length() - 1);
        cuts.add(full.length());

        for (int cut : cuts) {
            if (cut < 0 || cut > full.length()) {
                continue;
            }
            String slice = full.substring(0, cut);
            StageResult result = MachineUiStage.INSTANCE.process(slice, FilterContext.empty());
            assertThat(result).as("cut %s", cut).isNotNull();
            assertThat(result.output()).as("cut %s", cut).isNotNull();
            if (slice.contains("\"addr\":\"aws_instance.web\"") && result.shortCircuit()) {
                assertThat(result.output()).contains("aws_instance.web");
            }
        }
    }

    @Test
    void midObjectAndNonJsonNeverThrow() {
        String[] hostile = {
            "{",
            "{\"type\":\"",
            "{\"type\":\"version\",\"ui\":\"1.0\"",
            "[]\n{\"type\":\"version\",\"ui\":\"1.0\",\"@module\":\"terraform.ui\"}",
            "hello\n{",
            ""
        };
        for (String input : hostile) {
            StageResult result = MachineUiStage.INSTANCE.process(input, FilterContext.empty());
            assertThat(result.shortCircuit()).as(input).isFalse();
            assertThat(result.output()).isEqualTo(input);
        }
    }

    private static String read(String resource) {
        try (var in = MachineUiFuzzTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(resource, e);
        }
    }
}
